package io.github.sisyfyzhang.steadyrpc.core.filter;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.github.sisyfyzhang.steadyrpc.core.Deadline;
import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;

/**
 * 一次调用在 Filter 链里流转的描述。客户端由代理在发起时构造（attachments 来自 RpcContext 快照），
 * 服务端由分发器在收到请求后构造（attachments 来自帧，IO 线程已解码）。
 *
 * <p>attachments 可被 Filter 改写（例如 trace Filter 写入 traceparent），其余字段不可变。
 *
 * <p>时间：{@link #startNanos()} 在客户端是调用发起时刻，在服务端是 IO 线程解码该请求帧的时刻（到达时间）；
 * 都取 {@link System#nanoTime()}，不受墙钟调整影响。{@code timeout} 为 {@link Duration#ZERO} 表示不限；
 * 服务端的 timeout 来自 {@code rpc.timeout} attachment。
 *
 * <p>服务端的参数是惰性的：{@link #args()} 第一次被调用时才在当前（业务）线程反序列化。deadline、过载等 Filter
 * 在拒绝请求时不需要为它付出反序列化的成本（D-006 的出发点）。
 */
public final class Invocation {

	private final ServiceKey serviceKey;
	private final Method method;
	private final Supplier<Object[]> argsSource;
	private Object[] args;
	private final Map<String, String> attachments;
	private final Duration timeout;
	private final long startNanos;
	private final boolean inbound;
	private volatile boolean targetInvoked;
	/** 客户端：放弃本次调用时要执行的动作（null / 单个 Consumer / List）；放弃之后记下原因。见 {@link #onAbandon}。 */
	private Object abandonHooks;
	private Throwable abandonCause;

	/** 客户端（以及测试）使用：参数已知，起始时间为现在。 */
	public Invocation(ServiceKey serviceKey, Method method, Object[] args, Map<String, String> attachments, Duration timeout) {
		this(serviceKey, method, null, args == null ? new Object[0] : args, attachments, timeout, System.nanoTime(), false);
	}

	private Invocation(ServiceKey serviceKey, Method method, Supplier<Object[]> argsSource, Object[] args,
			Map<String, String> attachments, Duration timeout, long startNanos, boolean inbound) {
		this.serviceKey = serviceKey;
		this.method = method;
		this.argsSource = argsSource;
		this.args = args;
		this.attachments = attachments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attachments);
		this.timeout = timeout == null ? Duration.ZERO : timeout;
		this.startNanos = startNanos;
		this.inbound = inbound;
	}

	/**
	 * 服务端分发器使用：参数由 {@code args} 惰性提供（最多调用一次），起始时间为请求到达时间。
	 */
	public static Invocation inbound(ServiceKey serviceKey, Method method, Supplier<Object[]> args,
			Map<String, String> attachments, Duration timeout, long arrivalNanos) {
		return new Invocation(serviceKey, method, args, null, attachments, timeout, arrivalNanos, true);
	}

	public ServiceKey serviceKey() {
		return serviceKey;
	}

	public Method method() {
		return method;
	}

	public String methodName() {
		return method.getName();
	}

	/**
	 * 调用参数。服务端首次访问时反序列化；反序列化失败抛出的异常由框架映射为 SERIALIZATION_ERROR / BAD_REQUEST。
	 */
	public synchronized Object[] args() {
		if (args == null) {
			args = argsSource.get();
		}
		return args;
	}

	/** 只读视图；改写请用 {@link #setAttachment}，以便以后在这里统一做键名 / 长度校验。 */
	public Map<String, String> attachments() {
		return Collections.unmodifiableMap(attachments);
	}

	public String getAttachment(String key) {
		return attachments.get(key);
	}

	public void setAttachment(String key, String value) {
		if (key == null || value == null) {
			throw new IllegalArgumentException("attachment key/value must not be null");
		}
		attachments.put(key, value);
	}

	public Duration timeout() {
		return timeout;
	}

	/** 客户端：发起时刻；服务端：请求到达（IO 线程解码）时刻。{@link System#nanoTime()} 时钟。 */
	public long startNanos() {
		return startNanos;
	}

	/** 是否为服务端收到的调用。 */
	public boolean isInbound() {
		return inbound;
	}

	/** 截止时刻 {@code startNanos + timeout}；timeout 为 0（不限）时为 null。 */
	public Deadline deadline() {
		return timeout.isZero() ? null : Deadline.atNanoTime(startNanos + timeout.toNanos());
	}

	/**
	 * 服务端：业务方法是否已经被调用。框架在反射调用业务方法之前置位。分发器据此区分"请求被拒绝（业务未执行）"与
	 * "业务方法执行后失败"：前者按异常的 {@code RpcException.status()} 回包（如 SERVER_OVERLOADED，客户端可安全换节点
	 * 重试），后者一律按业务结果处理。Filter 也可以用它区分两类结果（例如指标里分开统计"被拒绝"）。
	 */
	public boolean targetInvoked() {
		return targetInvoked;
	}

	/** 框架内部使用：链尾在调用业务方法之前调用。 */
	public void markTargetInvoked() {
		this.targetInvoked = true;
	}

	/**
	 * 框架内部使用（客户端发送层）：登记"调用方放弃本次调用时，如何让已发出的请求失败"。每次发送尝试登记一次；
	 * 调用已被放弃时立即以放弃原因执行（迟到的重试同样立即失败）。hook 不应抛异常，也不应阻塞。
	 */
	public void onAbandon(Consumer<? super Throwable> hook) {
		Throwable cause;
		synchronized (this) {
			cause = abandonCause;
			if (cause == null) {
				if (abandonHooks == null) {
					abandonHooks = hook;
				} else if (abandonHooks instanceof List<?> list) {
					@SuppressWarnings("unchecked")
					List<Object> hooks = (List<Object>) list;
					hooks.add(hook);
				} else {
					List<Object> hooks = new ArrayList<>(2);
					hooks.add(abandonHooks);
					hooks.add(hook);
					abandonHooks = hooks;
				}
				return;
			}
		}
		hook.accept(cause);
	}

	/**
	 * 框架内部使用（同步代理）：调用方不再等待本次调用（deadline 定时器没有按时触发，见
	 * {@code RpcClientConfig.syncWaitGrace}），以 {@code cause} 执行所有已登记的 {@link #onAbandon} 动作。只生效一次。
	 */
	@SuppressWarnings("unchecked")
	public void abandon(Throwable cause) {
		Object hooks;
		synchronized (this) {
			if (abandonCause != null) {
				return;
			}
			abandonCause = cause;
			hooks = abandonHooks;
			abandonHooks = null;
		}
		if (hooks instanceof List<?> list) {
			for (Object hook : list) {
				((Consumer<? super Throwable>) hook).accept(cause);
			}
		} else if (hooks != null) {
			((Consumer<? super Throwable>) hooks).accept(cause);
		}
	}

	/** 距 {@code startNanos + timeout} 的剩余纳秒，可能为负；timeout 为 0（不限）时返回 {@link Long#MAX_VALUE}。 */
	public long remainingNanos() {
		if (timeout.isZero()) {
			return Long.MAX_VALUE;
		}
		return startNanos + timeout.toNanos() - System.nanoTime();
	}

	@Override
	public String toString() {
		return serviceKey + "#" + method.getName();
	}
}
