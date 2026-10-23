package io.github.sisyfyzhang.steadyrpc.bench;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import io.github.sisyfyzhang.steadyrpc.core.RpcDeadlineExceededException;
import io.github.sisyfyzhang.steadyrpc.core.RpcException;

/**
 * 错误分桶：{@code timeout}（{@link RpcDeadlineExceededException}；HTTP 目标等不到响应时的
 * {@link java.util.concurrent.TimeoutException}）、{@code status:<Status>}（其余 {@link RpcException}，
 * 按 {@link RpcException#status()}）、{@code payload_mismatch}（回显内容不对）、{@code http_status:<code>}（HTTP 目标回了非 200）、
 * 对比对象抛出的 {@link BenchCallException} 按它自带的桶名（如 {@code grpc_status:UNAVAILABLE}）、
 * {@code transport}（本框架客户端自己产生的传输失败：连不上、没有可用连接、连接在回包前断开、写失败、出站硬上限未发出、客户端已关闭——
 * 它们是基类 {@link RpcException}，状态码是缺省的 SERVER_ERROR，以前落进 {@code status:SERVER_ERROR}，与服务端回的错误混在一起）、
 * {@code no_provider}（提供者列表为空）、
 * 其它异常记完整类名（{@code backlog_dropped} 由开环驱动直接记，不经这里）。
 */
final class ErrorClassifier {

	static final String TIMEOUT = "timeout";
	static final String PAYLOAD_MISMATCH = "payload_mismatch";
	static final String TRANSPORT = "transport";
	static final String NO_PROVIDER = "no_provider";
	/**
	 * 本框架客户端在传输层自己产生的失败的消息前缀（{@code NettyClient} / {@code ConnectionPool} / {@code ConnectionManager} /
	 * {@code ClientHandler} / {@code RpcClient}）：都是基类 {@link RpcException}，靠消息区分；改了这些消息要同步这里（有测试守着）。
	 */
	static final java.util.List<String> TRANSPORT_PREFIXES = java.util.List.of("No active connection to ", "Failed to connect to ",
			"No connection pool for: ", "Connection to ", "Failed to send request to ", "Send buffer to ", "RpcClient closed");
	static final String NO_PROVIDER_PREFIX = "No available provider for: ";

	private ErrorClassifier() {
	}

	static String classify(Throwable error) {
		Throwable t = unwrap(error);
		if (t instanceof RpcDeadlineExceededException) {
			return TIMEOUT;
		}
		if (t instanceof RpcException rpc) {
			if (t.getClass() == RpcException.class && t.getMessage() != null) {
				String message = t.getMessage();
				if (message.startsWith(NO_PROVIDER_PREFIX)) {
					return NO_PROVIDER;
				}
				for (String prefix : TRANSPORT_PREFIXES) {
					if (message.startsWith(prefix)) {
						return TRANSPORT;
					}
				}
			}
			return "status:" + rpc.status();
		}
		if (t instanceof PayloadMismatchException) {
			return PAYLOAD_MISMATCH;
		}
		if (t instanceof BenchCallException call) {
			// 对比对象（gRPC 等）把自己框架的错误映射成可比的桶（超时 = timeout，其余带前缀的状态码）
			return call.bucket();
		}
		if (t instanceof java.util.concurrent.TimeoutException) {
			return TIMEOUT;
		}
		if (t instanceof HttpStatusException status) {
			return "http_status:" + status.code;
		}
		return t.getClass().getName();
	}

	private static Throwable unwrap(Throwable error) {
		Throwable t = error;
		while ((t instanceof CompletionException || t instanceof ExecutionException || t instanceof UndeclaredThrowableException
				|| t instanceof InvocationTargetException) && t.getCause() != null) {
			t = t.getCause();
		}
		return t;
	}

	/** 服务端回显的内容与发出的不一致：调用"成功"了但结果是错的，必须算错误而不是算吞吐。 */
	static final class PayloadMismatchException extends BenchCallException {
		private static final long serialVersionUID = 1L;

		PayloadMismatchException(String message) {
			super(PAYLOAD_MISMATCH, message, null);
		}
	}

	/** HTTP 目标回了非 200：调用"完成"了但没有成功，按状态码分桶。 */
	static final class HttpStatusException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		final int code;

		HttpStatusException(int code) {
			super("HTTP status " + code, null, false, false);
			this.code = code;
		}
	}
}
