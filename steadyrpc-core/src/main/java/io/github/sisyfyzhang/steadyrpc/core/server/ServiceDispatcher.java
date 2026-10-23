package io.github.sisyfyzhang.steadyrpc.core.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sisyfyzhang.steadyrpc.core.RpcContext;
import io.github.sisyfyzhang.steadyrpc.core.RpcException;
import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.codec.AttachmentsCodec;
import io.github.sisyfyzhang.steadyrpc.core.codec.EnvelopeCodec;
import io.github.sisyfyzhang.steadyrpc.core.config.RpcServerConfig;
import io.github.sisyfyzhang.steadyrpc.core.codec.EnvelopeCodec.RequestEnvelope;
import io.github.sisyfyzhang.steadyrpc.core.filter.Filter;
import io.github.sisyfyzhang.steadyrpc.core.filter.FilterChain;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invocation;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invoker;
import io.github.sisyfyzhang.steadyrpc.core.filter.Result;
import io.github.sisyfyzhang.steadyrpc.core.protocol.AttachmentKeys;
import io.github.sisyfyzhang.steadyrpc.core.protocol.MethodSignature;
import io.github.sisyfyzhang.steadyrpc.core.protocol.OutboundBody;
import io.github.sisyfyzhang.steadyrpc.core.protocol.ProtocolConstants;
import io.github.sisyfyzhang.steadyrpc.core.protocol.ProtocolException;
import io.github.sisyfyzhang.steadyrpc.core.protocol.RpcMessage;
import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;
import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializer;
import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

/**
 * 服务端的方法表与请求处理：把一帧请求解成 {@link Invocation}，经服务端 Filter 链，最终反射调用业务实现。
 * 方法表归所属 RpcServer 所有，同 JVM 的两个服务端各自只路由到自己暴露的实现。
 *
 * <p>路由键 = {@code interface:version:group}（ServiceKey）+ {@code method(paramSig)}，export 时按此预建方法表：
 * 同一接口可以按不同 version / group 暴露多份，重载方法按参数签名区分（paramSig 规则见 {@link MethodSignature}）。
 *
 * <p>状态码映射：信封 / 参数个数错误 → BAD_REQUEST；服务或方法不存在 → SERVICE_NOT_FOUND；不支持的序列化编码 →
 * SERIALIZATION_ERROR。之后按"业务方法是否已被调用"（{@link Invocation#targetInvoked()}）分两类：
 * <ul>
 *   <li><b>业务方法未执行</b>（Filter 拒绝、参数解不出来）：异常是 {@link RpcException} 就按它的
 *       {@link RpcException#status()} 回包（DeadlineServerFilter → DEADLINE_EXCEEDED、参数反序列化失败 →
 *       SERIALIZATION_ERROR、过载 Filter → SERVER_OVERLOADED），状态码随异常走，这里没有 instanceof 链；
 *       其他异常让 future 失败的 → SERVER_ERROR，装进 Result 的 → BIZ_EXCEPTION。</li>
 *   <li><b>业务方法已执行</b>：{@link Error} 与 RpcException（多半是业务方法里的下游调用失败）→ SERVER_ERROR；
 *       其余异常 → BIZ_EXCEPTION（不打 error 堆栈）。不沿用下游异常自带的状态：下游的 SERVER_OVERLOADED /
 *       DEADLINE_EXCEEDED 都承诺"业务没执行"，原样转给上游会让它把一个已经执行过的请求当成可安全重试。</li>
 * </ul>
 * 回包本身发不出去的两种情况也在这里改写，而不是留给编码器写失败（那样客户端只能等到超时）：回程 attachments
 * 超限或含非法值 → 不带 attachments 的 SERVER_ERROR；OK 回包整帧超过 MAX_FRAME_LENGTH → SERIALIZATION_ERROR。
 * 序列化返回值时抛出 Error（对象图过深的 StackOverflowError 等）同样回 SERIALIZATION_ERROR（与客户端把请求序列化的
 * Error 当作确定性的序列化失败一致）；组装回包的其余步骤抛出任何 Throwable 都退回一个只带消息的 SERVER_ERROR——
 * 回包总会产出，客户端不会因此挂到超时。
 *
 * <p><b>回包不在业务线程上分配缓冲区</b>：业务线程（可能是虚拟线程，见 {@code ExecutorConfig.virtualThreads}）只把
 * 返回值序列化成 byte[]、把错误信封的字段备好，回包携带 {@link OutboundBody}，由编码器在连接的事件循环上按确切长度
 * 一次分配整帧。这里因此也没有"已分配、还没交给 Netty"的缓冲区，Error 路径不会泄漏。
 *
 * <p><b>服务端异步到底</b>：业务方法返回 {@link CompletionStage}（含 CompletableFuture）时不在业务线程上等待：
 * 业务线程在方法返回时立即释放去处理下一个请求，future 完成时在<b>完成它的那个线程</b>上序列化结果并写回响应；
 * 异常完成按上面的规则映射。入站 attachments / deadline 的线程绑定在业务方法返回时就解除（见 RpcContext）。
 * 代价：结果序列化发生在完成 future 的线程上（可能是业务自己的线程池，也可能是一个下游调用的 IO 线程），
 * 大对象的序列化不该落在 IO 线程上时，业务应在自己的执行器上完成 future。
 *
 * <p>引用计数：{@link #handle} 接管请求消息，所有路径（找不到服务、信封错误、Filter 抛异常、业务异常、
 * 序列化失败）都恰好释放一次；参数是 body 上的 slice，在 Filter 链完成之后才释放。框架内部类型。
 */
public final class ServiceDispatcher {

	private static final Logger log = LoggerFactory.getLogger(ServiceDispatcher.class);

	private final List<Filter> filters;
	private final Serializers serializers;
	private final boolean remoteStackTrace;
	private final int remoteStackTraceMaxBytes;
	private final Map<ServiceKey, ExportedService> services = new ConcurrentHashMap<>();

	public ServiceDispatcher(List<? extends Filter> filters, Serializers serializers, boolean remoteStackTrace) {
		this(filters, serializers, remoteStackTrace, RpcServerConfig.DEFAULT_REMOTE_STACK_TRACE_MAX_BYTES);
	}

	/** @param remoteStackTraceMaxBytes 回传堆栈的 UTF-8 字节上限，超出在字符边界截断并以截断标记结尾 */
	public ServiceDispatcher(List<? extends Filter> filters, Serializers serializers, boolean remoteStackTrace,
			int remoteStackTraceMaxBytes) {
		this.filters = FilterChain.ordered(filters);
		this.serializers = serializers;
		this.remoteStackTrace = remoteStackTrace;
		this.remoteStackTraceMaxBytes = remoteStackTraceMaxBytes;
	}

	public Serializers serializers() {
		return serializers;
	}

	public synchronized void register(ServiceKey key, Class<?> itf, Object impl) {
		if (services.containsKey(key)) {
			throw new IllegalStateException(key + " is already exported on this server");
		}
		Invoker chain = FilterChain.build(filters, invocation -> invokeTarget(impl, invocation));
		Map<String, ProviderMethod> methods = new HashMap<>();
		for (Method method : itf.getMethods()) {
			if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())) {
				continue;
			}
			String methodKey = MethodSignature.methodKey(method.getName(), MethodSignature.paramSig(method));
			ProviderMethod existing = methods.get(methodKey);
			if (existing != null) {
				// 同签名只可能来自多个父接口声明了同一方法（返回类型协变）：保留返回类型更具体的那个
				if (existing.method.getReturnType().isAssignableFrom(method.getReturnType())) {
					methods.put(methodKey, new ProviderMethod(method));
				} else if (!method.getReturnType().isAssignableFrom(existing.method.getReturnType())) {
					throw new IllegalArgumentException("Ambiguous method " + methodKey + " in " + itf.getName());
				}
				continue;
			}
			methods.put(methodKey, new ProviderMethod(method));
		}
		services.put(key, new ExportedService(key, Map.copyOf(methods), chain));
	}

	/**
	 * 处理一帧请求（在业务线程上调用），返回待写回的响应帧。不抛异常；{@code request} 恰好被释放一次。
	 * 业务方法同步返回时，返回的 future 在本方法返回前已完成；业务方法返回未完成的 future 时，本方法立即返回，
	 * 响应帧在业务 future 完成时（在完成它的线程上）产出。
	 */
	public CompletableFuture<RpcMessage> handle(RpcMessage request) {
		ReleaseOnce owner = new ReleaseOnce(request);
		try {
			return dispatch(request, owner);
		} catch (Throwable t) {
			owner.release();
			log.error("Unexpected error handling request {}", request.requestId(), t);
			return done(replyOrFallback(request, () -> errorReply(request, Status.SERVER_ERROR, t, true, Map.of())));
		}
	}

	private CompletableFuture<RpcMessage> dispatch(RpcMessage request, ReleaseOnce owner) {
		if ((request.flags() & ProtocolConstants.FLAG_COMPRESSED) != 0) {
			owner.release();
			return done(frameworkError(request, Status.BAD_REQUEST, "compressed body is not supported"));
		}
		Serializer serializer = serializers.find(request.serializer());
		if (serializer == null) {
			owner.release();
			return done(frameworkError(request, Status.SERIALIZATION_ERROR,
					"unsupported serializer code " + request.serializer()));
		}
		RequestEnvelope envelope;
		ServiceKey key;
		try {
			envelope = EnvelopeCodec.readRequest(request.body());
			key = new ServiceKey(envelope.service(), envelope.version(), envelope.group());
		} catch (ProtocolException | IllegalArgumentException e) {
			owner.release();
			return done(frameworkError(request, Status.BAD_REQUEST, "malformed request envelope: " + e.getMessage()));
		}
		ExportedService service = services.get(key);
		if (service == null) {
			owner.release();
			return done(frameworkError(request, Status.SERVICE_NOT_FOUND, "no such service: " + key));
		}
		String methodKey = MethodSignature.methodKey(envelope.method(), envelope.paramSig());
		ProviderMethod providerMethod = service.methods().get(methodKey);
		if (providerMethod == null) {
			owner.release();
			return done(frameworkError(request, Status.SERVICE_NOT_FOUND, "no such method: " + key + "#" + methodKey));
		}
		if (envelope.args().size() != providerMethod.paramTypes.length) {
			owner.release();
			return done(frameworkError(request, Status.BAD_REQUEST, "argument count mismatch for " + methodKey
					+ ": expected " + providerMethod.paramTypes.length + ", got " + envelope.args().size()));
		}

		Invocation invocation = Invocation.inbound(service.key(), providerMethod.method,
				() -> decodeArgs(envelope.args(), providerMethod, serializer, owner),
				request.attachments(), timeoutOf(request.attachments()), request.receivedNanos());
		return service.chain().invoke(invocation)
				.handle((result, error) -> replyOrFallback(request,
						() -> toReply(request, serializer, invocation, providerMethod, result, error)))
				.whenComplete((reply, error) -> owner.release());
	}

	/**
	 * 不经过 Filter 链、直接以框架错误回包（过载 / 停机拒绝时用 SERVER_OVERLOADED），避免客户端等到超时。
	 * 调用方负责释放请求。
	 */
	public RpcMessage frameworkErrorReply(RpcMessage request, Status status, String reason) {
		return frameworkError(request, status, reason);
	}

	/** attachments 段格式错误 / 超限时的回包。调用方负责释放请求。 */
	public RpcMessage badAttachmentsReply(RpcMessage request) {
		return frameworkError(request, Status.BAD_REQUEST, "malformed attachments: " + request.malformedAttachments());
	}

	/**
	 * 组装回包的最后一道兜底：任何 Throwable（例如异常的 getMessage / 堆栈打印本身抛出 Error）都退回一个只带类名的
	 * SERVER_ERROR，而不是让 future 异常完成——那样 ServerHandler 没有可写的回包，客户端只能等到超时。
	 */
	private static RpcMessage replyOrFallback(RpcMessage request, Supplier<RpcMessage> reply) {
		try {
			return reply.get();
		} catch (Throwable t) {
			log.error("Failed to build the reply for request {}; replying SERVER_ERROR", request.requestId(), t);
			return frameworkError(request, Status.SERVER_ERROR, "failed to build reply: " + t.getClass().getName());
		}
	}

	private Object[] decodeArgs(List<ByteBuf> encoded, ProviderMethod providerMethod, Serializer serializer, ReleaseOnce owner) {
		if (owner.isReleased()) {
			throw new IllegalStateException("request body already released; Invocation.args() must be read before the chain completes");
		}
		Object[] args = new Object[encoded.size()];
		for (int i = 0; i < args.length; i++) {
			Class<?> rawType = providerMethod.paramTypes[i];
			ByteBuf bytes = encoded.get(i);
			if (bytes == null) {
				if (rawType.isPrimitive()) {
					throw new ArgumentDecodeException("argument " + i + " is null but parameter type is " + rawType, null);
				}
				continue;
			}
			Object value;
			try {
				value = serializer.deserialize(ByteBufUtil.getBytes(bytes), providerMethod.genericParamTypes[i]);
			} catch (RuntimeException e) {
				throw new ArgumentDecodeException("failed to deserialize argument " + i + " as "
						+ providerMethod.genericParamTypes[i].getTypeName() + " with " + serializer.name() + ": " + e, e);
			}
			if (value == null ? rawType.isPrimitive() : !wrap(rawType).isInstance(value)) {
				throw new ArgumentDecodeException("argument " + i + " decoded as "
						+ (value == null ? "null" : value.getClass().getName()) + ", expected " + rawType.getName(), null);
			}
			args[i] = value;
		}
		return args;
	}

	private RpcMessage toReply(RpcMessage request, Serializer serializer, Invocation invocation,
			ProviderMethod providerMethod, Result result, Throwable error) {
		Map<String, String> replyAttachments = result == null ? Map.of() : result.attachments();
		int attachmentsLength;
		try {
			attachmentsLength = AttachmentsCodec.encodedLength(replyAttachments, ProtocolConstants.DEFAULT_MAX_ATTACHMENTS_LENGTH);
		} catch (IllegalArgumentException e) {
			// 回程 attachments 超限（> 64 KiB）或含非法值（null 等）：原样交给编码器只会写失败、回包丢失，客户端等到超时。
			// 改回不带 attachments 的 SERVER_ERROR——问题出在服务端 Filter / 业务写的元数据上，与序列化器无关，
			// 且业务方法可能已执行（SERVER_ERROR 不重试）；原结果随之丢弃，不静默去掉 attachments 假装成功。
			log.warn("Invalid response attachments for {} (request {}), replying SERVER_ERROR without attachments: {}",
					invocation, request.requestId(), e.getMessage());
			return frameworkError(request, Status.SERVER_ERROR, "invalid response attachments: " + e.getMessage());
		}
		if (error != null) {
			// Filter / 链尾让 future 失败：框架侧问题（参数解不出来、反射失败、Filter 抛异常）
			Throwable failure = unwrap(error);
			Status status = failure instanceof RpcException rpc && !invocation.targetInvoked() ? rpc.status() : Status.SERVER_ERROR;
			if (status == Status.SERVER_ERROR) {
				log.error("Filter chain failed for {} (request {})", invocation, request.requestId(), failure);
			} else {
				log.warn("Rejected {} (request {}) with {}: {}", invocation, request.requestId(), status, failure.getMessage());
			}
			return errorReply(request, status, failure, isTyped(status), replyAttachments);
		}
		if (result == null) {
			log.error("Filter chain produced no result for {} (request {})", invocation, request.requestId());
			return frameworkError(request, Status.SERVER_ERROR, "filter chain produced no result");
		}
		if (result.hasException()) {
			Throwable failure = result.exception();
			Status status = statusOf(failure, invocation.targetInvoked());
			switch (status) {
				case BIZ_EXCEPTION -> {
					// 业务异常是调用结果的一部分，不是服务端故障：不打 error、不打堆栈（堆栈随回包交给调用方）。
					// 方法签名里声明过的异常是约定内的结果，只打 debug；未声明的（多半是 bug）打一行 warn。
					if (isDeclared(providerMethod.method, failure)) {
						log.debug("{} (request {}) threw declared {}: {}", invocation, request.requestId(),
								failure.getClass().getName(), failure.getMessage());
					} else {
						log.warn("{} (request {}) threw {}: {}", invocation, request.requestId(),
								failure.getClass().getName(), failure.getMessage());
					}
				}
				case SERVER_ERROR -> {
					if (failure instanceof Error) {
						log.error("Error from {} (request {})", invocation, request.requestId(), failure);
					} else {
						log.warn("{} (request {}) failed with {}: {}", invocation, request.requestId(),
								failure.getClass().getName(), failure.getMessage());
					}
				}
				default -> log.debug("Rejected {} (request {}) with {}: {}", invocation, request.requestId(), status,
						failure.getMessage());
			}
			return errorReply(request, status, failure, isTyped(status), replyAttachments);
		}
		OutboundBody body;
		try {
			Object value = result.value();
			byte[] bytes = value == null || providerMethod.voidReturn ? null
					: serializer.serialize(value, providerMethod.returnValueType);
			// 只备好字节，不分配缓冲区：整帧由编码器在事件循环上按确切长度写出
			body = EnvelopeCodec.value(bytes);
			// 与客户端发送前检查、RpcEncoder 同一判定（23 字节头 + attachments + body ≤ MAX_FRAME_LENGTH）：
			// 超限时回 SERIALIZATION_ERROR，而不是交给编码器写失败、让客户端等到超时
			if (ProtocolConstants.exceedsMaxFrame(attachmentsLength, body.length())) {
				throw new IllegalStateException("result too large: frame of " + (ProtocolConstants.HEADER_LENGTH
						+ (long) attachmentsLength + body.length()) + " bytes exceeds MAX_FRAME_LENGTH "
						+ ProtocolConstants.MAX_FRAME_LENGTH);
			}
		} catch (RuntimeException e) {
			log.error("Failed to serialize result of {} (request {})", invocation, request.requestId(), e);
			return errorReply(request, Status.SERIALIZATION_ERROR, e, false, replyAttachments);
		} catch (Error e) {
			// 对象图过深的 StackOverflowError 等：同样是返回值发不出去，回 SERIALIZATION_ERROR（业务方法已执行，不重试），
			// 而不是让 future 异常完成、客户端挂到超时。消息里带上 Error 的类型——它的 message 常为 null
			log.error("Error while serializing result of {} (request {})", invocation, request.requestId(), e);
			return frameworkError(request, Status.SERIALIZATION_ERROR, replyAttachments,
					"failed to serialize result: " + e.getClass().getName() + ": " + e.getMessage());
		}
		return RpcMessage.response(request.requestId(), serializer.code(), Status.OK, replyAttachments, body);
	}

	/** Result 里的异常 → 回包状态（规则见类注释）。 */
	private static Status statusOf(Throwable failure, boolean targetInvoked) {
		if (failure instanceof Error) {
			return Status.SERVER_ERROR;
		}
		if (failure instanceof RpcException rpc) {
			return targetInvoked ? Status.SERVER_ERROR : rpc.status();
		}
		return Status.BIZ_EXCEPTION;
	}

	/** BIZ_EXCEPTION / SERVER_ERROR 回包带异常类名与堆栈（客户端据此还原类型）；拒绝类状态只带消息。 */
	private static boolean isTyped(Status status) {
		return status == Status.BIZ_EXCEPTION || status == Status.SERVER_ERROR;
	}

	/**
	 * 链尾：反射调用业务实现。业务异常装进 Result，框架侧失败（参数解不出来、反射本身失败）让 future 失败。
	 *
	 * <p>业务方法返回 {@link CompletionStage} 时直接把它接到返回的 future 上，不 join：业务线程随本方法返回而释放。
	 * 入站上下文在 finally 里解绑——也就是业务方法<b>返回</b>时，而不是 future 完成时（那时业务线程早已在处理别的请求，
	 * 等到那时才解绑会把上一个请求的 attachments 留给下一个请求）。
	 */
	private static CompletableFuture<Result> invokeTarget(Object impl, Invocation invocation) {
		Object[] args;
		try {
			args = invocation.args();
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		RpcContext.bindServerContext(invocation.attachments(), invocation.deadline());
		Object ret;
		try {
			invocation.markTargetInvoked();
			ret = invocation.method().invoke(impl, args);
		} catch (InvocationTargetException e) {
			return CompletableFuture.completedFuture(Result.exception(e.getCause() != null ? e.getCause() : e));
		} catch (IllegalAccessException | IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		} finally {
			RpcContext.unbindServerContext();
		}
		if (!(ret instanceof CompletionStage<?> stage)) {
			return CompletableFuture.completedFuture(Result.value(ret));
		}
		CompletableFuture<Result> result = new CompletableFuture<>();
		try {
			stage.whenComplete((value, failure) -> result.complete(failure == null
					? Result.value(value)
					: Result.exception(failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure)));
		} catch (RuntimeException e) {
			// 自定义的 CompletionStage 实现可能在注册回调时就抛异常
			result.complete(Result.exception(e));
		}
		return result;
	}

	/**
	 * @param typed true：信封带异常类名与（按配置）堆栈，客户端据此还原类型；false：只带消息（框架错误）
	 */
	/** 错误信封只在这里备好字段（截断、取堆栈），缓冲区由编码器在事件循环上分配。 */
	private RpcMessage errorReply(RpcMessage request, Status status, Throwable failure, boolean typed,
			Map<String, String> attachments) {
		OutboundBody body = typed
				? EnvelopeCodec.error(failure.getClass().getName(), failure.getMessage(),
						remoteStackTrace ? limit(stackTrace(failure), remoteStackTraceMaxBytes) : "")
				: EnvelopeCodec.error("", String.valueOf(failure.getMessage()), "");
		return RpcMessage.response(request.requestId(), request.serializer(), status, attachments, body);
	}

	private static RpcMessage frameworkError(RpcMessage request, Status status, String message) {
		return frameworkError(request, status, Map.of(), message);
	}

	private static RpcMessage frameworkError(RpcMessage request, Status status, Map<String, String> attachments,
			String message) {
		return RpcMessage.response(request.requestId(), request.serializer(), status, attachments,
				EnvelopeCodec.error("", message, ""));
	}

	private static CompletableFuture<RpcMessage> done(RpcMessage reply) {
		return CompletableFuture.completedFuture(reply);
	}

	/** 服务端 Invocation 的 timeout 来自 rpc.timeout；已耗尽（<= 0）记为 1ns，表示"有截止且已到"，而不是"不限"。 */
	private static Duration timeoutOf(Map<String, String> attachments) {
		String value = attachments.get(AttachmentKeys.TIMEOUT);
		if (value == null) {
			return Duration.ZERO;
		}
		try {
			long millis = Long.parseLong(value);
			return millis > 0 ? Duration.ofMillis(millis) : Duration.ofNanos(1);
		} catch (NumberFormatException e) {
			return Duration.ZERO;
		}
	}

	private static boolean isDeclared(Method method, Throwable failure) {
		for (Class<?> declared : method.getExceptionTypes()) {
			if (declared.isInstance(failure)) {
				return true;
			}
		}
		return false;
	}

	private static String stackTrace(Throwable failure) {
		StringWriter out = new StringWriter();
		failure.printStackTrace(new PrintWriter(out));
		return out.toString();
	}

	/**
	 * 把堆栈文本限制在 {@code maxBytes} 个 UTF-8 字节内（含截断标记）：超出时在字符边界截断（不切开多字节字符与代理对），
	 * 并以 {@link RpcServerConfig#TRUNCATION_MARKER} 结尾，调用方一眼能看出堆栈不完整。
	 */
	static String limit(String stack, int maxBytes) {
		if (ByteBufUtil.utf8Bytes(stack) <= maxBytes) {
			return stack;
		}
		int markerBytes = ByteBufUtil.utf8Bytes(RpcServerConfig.TRUNCATION_MARKER);
		return EnvelopeCodec.truncateUtf8(stack, maxBytes - markerBytes) + RpcServerConfig.TRUNCATION_MARKER;
	}

	private static Throwable unwrap(Throwable error) {
		return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
	}

	private static Class<?> wrap(Class<?> type) {
		if (!type.isPrimitive()) {
			return type;
		}
		return switch (type.getName()) {
			case "int" -> Integer.class;
			case "long" -> Long.class;
			case "boolean" -> Boolean.class;
			case "double" -> Double.class;
			case "float" -> Float.class;
			case "short" -> Short.class;
			case "byte" -> Byte.class;
			case "char" -> Character.class;
			default -> Void.class;
		};
	}

	/** 返回值的声明类型：{@code CompletableFuture<T>} / {@code CompletionStage<T>} 取 T，其余取方法的泛型返回类型。 */
	public static Type returnValueType(Method method) {
		if (isAsync(method)) {
			Type generic = method.getGenericReturnType();
			if (generic instanceof ParameterizedType parameterized) {
				return parameterized.getActualTypeArguments()[0];
			}
			return Object.class;
		}
		return method.getGenericReturnType();
	}

	/** 方法是否以 {@code CompletableFuture<T>} / {@code CompletionStage<T>} 异步返回结果（两端按同一规则判定）。 */
	public static boolean isAsync(Method method) {
		Class<?> raw = method.getReturnType();
		return raw == CompletableFuture.class || raw == CompletionStage.class;
	}

	private static final class ProviderMethod {
		final Method method;
		final Class<?>[] paramTypes;
		final Type[] genericParamTypes;
		final Type returnValueType;
		final boolean voidReturn;

		ProviderMethod(Method method) {
			method.setAccessible(true);
			this.method = method;
			this.paramTypes = method.getParameterTypes();
			this.genericParamTypes = method.getGenericParameterTypes();
			this.returnValueType = returnValueType(method);
			this.voidReturn = returnValueType == void.class || returnValueType == Void.class;
		}
	}

	private record ExportedService(ServiceKey key, Map<String, ProviderMethod> methods, Invoker chain) {
	}

	/** 请求消息的唯一所有者：多条路径都可能要释放，保证只释放一次。 */
	private static final class ReleaseOnce {
		private final RpcMessage message;
		private final AtomicBoolean released = new AtomicBoolean();

		ReleaseOnce(RpcMessage message) {
			this.message = message;
		}

		void release() {
			if (released.compareAndSet(false, true)) {
				message.release();
			}
		}

		boolean isReleased() {
			return released.get();
		}
	}
}
