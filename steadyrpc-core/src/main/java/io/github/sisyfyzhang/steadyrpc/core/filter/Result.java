package io.github.sisyfyzhang.steadyrpc.core.filter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次调用的结果：返回值或异常二选一，外加回程 attachments。
 *
 * <p>为什么业务异常 / 服务端错误放在 Result 里，而不是让 future 异常完成：future 异常完成专门表示
 * "这次传输没成功"（连接失败、超时），Cluster 只对这类失败重试；服务端已经执行并明确返回了错误时
 * 不应该被重试（非幂等调用可能被执行两次）。两种失败用两个通道区分，Cluster 不需要猜。
 */
public final class Result {

	private final Object value;
	private final Throwable exception;
	private final Map<String, String> attachments;

	private Result(Object value, Throwable exception, Map<String, String> attachments) {
		this.value = value;
		this.exception = exception;
		this.attachments = attachments == null || attachments.isEmpty()
				? Collections.emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(attachments));
	}

	public static Result value(Object value) {
		return new Result(value, null, null);
	}

	public static Result exception(Throwable exception) {
		if (exception == null) {
			throw new IllegalArgumentException("exception must not be null");
		}
		return new Result(null, exception, null);
	}

	public Result withAttachments(Map<String, String> attachments) {
		return new Result(value, exception, attachments);
	}

	public boolean hasException() {
		return exception != null;
	}

	public Object value() {
		return value;
	}

	public Throwable exception() {
		return exception;
	}

	public Map<String, String> attachments() {
		return attachments;
	}

}
