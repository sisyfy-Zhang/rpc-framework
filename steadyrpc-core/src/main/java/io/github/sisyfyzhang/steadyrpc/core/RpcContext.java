package io.github.sisyfyzhang.steadyrpc.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 调用方为"下一次"远程调用附加 attachments（traceId、压测标记、幂等键等）的入口，以及服务端业务方法读取
 * 入站上下文（attachments、剩余 deadline）的入口。
 *
 * <p>为什么是 ThreadLocal + 发起时快照：同步代码里业务方不需要把上下文层层传参；而一旦调用发出，
 * attachments 就被拷进本次 {@code Invocation}，之后的 Filter、重试、异步回调都只读快照，
 * 不会因为调用线程随后改写 ThreadLocal 而串到别的请求上。
 *
 * <p>快照即清空：attachments 是"每次调用"的语义。若在发起后保留，线程池复用线程时上一个请求的
 * traceId 会泄漏给不相干的下一次调用——这是 Dubbo 早期 RpcContext 的经典坑，所以这里默认消费即清。
 *
 * <p>服务端入口：业务方法执行期间，{@link #serverAttachments()} 返回本次请求带来的入站 attachments（只读），
 * {@link #serverDeadline()} 返回本次请求剩余的预算。由框架在业务线程上调用业务方法前绑定、<b>业务方法返回时</b>
 * 清除（返回 CompletableFuture 的方法也是在返回那一刻清除，不等 future 完成——业务线程此时已经去处理别的请求了），
 * 所以只在业务方法的同步部分可见。业务方法若把工作切到别的线程，应在切换前读出需要的值；deadline 用
 * {@link #withDeadline(Deadline, Supplier)} 显式带到新线程上。
 *
 * <p><b>deadline 向下传递</b>：在服务端业务方法的同步部分（或 {@code withDeadline} 的作用域内）发起的出站调用，
 * 超时自动取"引用配置的超时"与"继承的剩余预算"的较小者（{@code ReferenceOptions.inheritDeadline}，默认开启）。
 * 上游已经放弃的调用，下游不该再按自己的长超时继续等。异步到底之后自动继承有一个缺口：业务方法返回 future、
 * 之后在别的线程（future 回调、自建线程池）上发起的出站调用看不到 ThreadLocal——这种场景先在同步部分取出
 * {@code serverDeadline()}，再在新线程上用 {@code withDeadline} 包住出站调用。
 *
 * <p>这是 steadyrpc-core 里唯一允许的可变静态状态之一（ArchUnit 白名单）：ThreadLocal 本身就是
 * "每线程一份"，没有实例化的意义。
 */
public final class RpcContext {

	private static final ThreadLocal<Map<String, String>> ATTACHMENTS = new ThreadLocal<>();
	private static final ThreadLocal<Map<String, String>> SERVER_ATTACHMENTS = new ThreadLocal<>();
	private static final ThreadLocal<Deadline> SERVER_DEADLINE = new ThreadLocal<>();
	private static final ThreadLocal<Deadline> SCOPED_DEADLINE = new ThreadLocal<>();

	private RpcContext() {
	}

	/** 为当前线程发起的下一次 RPC 调用附加一个键值对；key / value 都不允许为 null（与协议 v2 的字符串 map 约束一致）。 */
	public static void setAttachment(String key, String value) {
		if (key == null || value == null) {
			throw new IllegalArgumentException("attachment key/value must not be null");
		}
		Map<String, String> map = ATTACHMENTS.get();
		if (map == null) {
			map = new LinkedHashMap<>();
			ATTACHMENTS.set(map);
		}
		map.put(key, value);
	}

	public static String getAttachment(String key) {
		Map<String, String> map = ATTACHMENTS.get();
		return map == null ? null : map.get(key);
	}

	public static void removeAttachment(String key) {
		Map<String, String> map = ATTACHMENTS.get();
		if (map != null) {
			map.remove(key);
		}
	}

	public static void clearAttachments() {
		ATTACHMENTS.remove();
	}

	/** 框架在发起调用时调用：取走当前线程的 attachments 快照并清空 ThreadLocal。 */
	static Map<String, String> consumeAttachments() {
		Map<String, String> map = ATTACHMENTS.get();
		if (map == null || map.isEmpty()) {
			return Collections.emptyMap();
		}
		ATTACHMENTS.remove();
		return map;
	}

	/**
	 * 服务端：当前正在执行的请求带来的 attachments（只读）。不在服务端业务方法内调用时返回空 map。
	 */
	public static Map<String, String> serverAttachments() {
		Map<String, String> map = SERVER_ATTACHMENTS.get();
		return map == null ? Collections.emptyMap() : map;
	}

	/** 服务端：读取一个入站 attachment，不存在返回 null。 */
	public static String getServerAttachment(String key) {
		return serverAttachments().get(key);
	}

	/**
	 * 服务端：当前正在执行的请求的截止时刻（请求到达时刻 + 调用方传来的 {@code rpc.timeout}），可用
	 * {@link Deadline#remaining()} 读剩余预算。调用方没有传超时、或不在服务端业务方法的同步部分内时为空。
	 */
	public static Optional<Deadline> serverDeadline() {
		return Optional.ofNullable(SERVER_DEADLINE.get());
	}

	/**
	 * 在 {@code body} 执行期间，让当前线程发起的出站调用继承 {@code deadline}（与已继承的截止时间取较早者，
	 * 截止时间只会收紧）。用于异步业务：在业务方法的同步部分取出 {@link #serverDeadline()}，在别的线程上
	 * 发起下游调用时用它包住。作用域结束后恢复原值，可嵌套。{@code deadline} 为 null 时不改变任何东西。
	 *
	 * <p>为什么用作用域而不是"设置后一直生效"：线程池线程会被复用，忘记清除的 deadline 会让之后不相干的调用
	 * 莫名超时；作用域写法在异常路径上也一定恢复。
	 */
	public static <T> T withDeadline(Deadline deadline, Supplier<T> body) {
		if (deadline == null) {
			return body.get();
		}
		Deadline previous = SCOPED_DEADLINE.get();
		SCOPED_DEADLINE.set(deadline.earlier(previous));
		try {
			return body.get();
		} finally {
			if (previous == null) {
				SCOPED_DEADLINE.remove();
			} else {
				SCOPED_DEADLINE.set(previous);
			}
		}
	}

	/** 当前线程上出站调用应继承的截止时间：作用域内显式指定的与服务端入站的取较早者；都没有时为 null。 */
	static Deadline inheritedDeadline() {
		Deadline scoped = SCOPED_DEADLINE.get();
		Deadline server = SERVER_DEADLINE.get();
		return scoped == null ? server : scoped.earlier(server);
	}

	/**
	 * 框架内部使用：服务端分发器在业务线程上调用业务方法前绑定入站 attachments 与截止时间。业务代码不应调用。
	 * （分发器在另一个包里，Java 没有"友元"可见性，只能公开并注明。）
	 *
	 * @param deadline 本次请求的截止时刻；调用方没有传超时时为 null
	 */
	public static void bindServerContext(Map<String, String> attachments, Deadline deadline) {
		SERVER_ATTACHMENTS.set(attachments == null || attachments.isEmpty()
				? Collections.emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(attachments)));
		if (deadline == null) {
			SERVER_DEADLINE.remove();
		} else {
			SERVER_DEADLINE.set(deadline);
		}
	}

	/** 框架内部使用：业务方法返回后立即清除（不等它返回的 future 完成），避免线程池复用时串到下一个请求。 */
	public static void unbindServerContext() {
		SERVER_ATTACHMENTS.remove();
		SERVER_DEADLINE.remove();
	}
}
