package io.github.sisyfyzhang.steadyrpc.metrics.micrometer;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import io.github.sisyfyzhang.steadyrpc.core.RpcDeadlineExceededException;
import io.github.sisyfyzhang.steadyrpc.core.RpcException;
import io.github.sisyfyzhang.steadyrpc.core.RpcOverloadedException;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invocation;
import io.github.sisyfyzhang.steadyrpc.core.filter.Result;
import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;

/**
 * 一次调用的结果 → {@code status} tag。取值有界：协议 {@link Status} 的枚举名，外加客户端独有的 {@link #TIMEOUT} 与
 * {@link #TRANSPORT_FAILURE}。<b>绝不</b>使用异常类名（业务异常类型无界，会让时间序列数爆炸）。
 *
 * <p>客户端（{@link #client}）：
 * <ul>
 *   <li>成功 → {@code OK}；</li>
 *   <li>{@code Result.exception} 是 RpcDeadlineExceededException 且起因是本端等待超时（{@link TimeoutException}）→ {@code TIMEOUT}；
 *       服务端回 DEADLINE_EXCEEDED、或发送前预算已耗尽的，仍是 {@code DEADLINE_EXCEEDED}；</li>
 *   <li>{@code Result.exception} 是其它 RpcException → 它的 {@link RpcException#status()} 名；不是 RpcException（业务异常已按
 *       原类型还原）→ {@code BIZ_EXCEPTION}；</li>
 *   <li>future 失败：RpcOverloadedException（服务端回 SERVER_OVERLOADED，core 有意让它走传输失败通道以便 failover）→
 *       {@code SERVER_OVERLOADED}；其余（连不上、写失败、无可用提供者、连接中途断开）→ {@code TRANSPORT_FAILURE}。</li>
 * </ul>
 *
 * <p>服务端（{@link #server}）按 ServiceDispatcher 的回包规则推断（Filter 看不到链完成之后才发生的改写，见 docs/observability.md）：
 * Result 里的 Error → {@code SERVER_ERROR}；RpcException → 业务方法未执行时取它的 status，执行过则 {@code SERVER_ERROR}；
 * 其余异常 → {@code BIZ_EXCEPTION}；future 失败 → RpcException 且业务未执行时取它的 status，否则 {@code SERVER_ERROR}。
 */
final class CallStatus {

	static final String OK = Status.OK.name();
	/** 客户端：在截止时间前没等到响应（本端定时器触发）。 */
	static final String TIMEOUT = "TIMEOUT";
	/** 客户端：调用没有拿到服务端的答复（连接失败、写失败、无可用提供者、连接中途关闭等）。 */
	static final String TRANSPORT_FAILURE = "TRANSPORT_FAILURE";

	private CallStatus() {
	}

	static String client(Result result, Throwable error) {
		if (error != null) {
			return unwrap(error) instanceof RpcOverloadedException ? Status.SERVER_OVERLOADED.name() : TRANSPORT_FAILURE;
		}
		if (result == null) {
			return Status.SERVER_ERROR.name();
		}
		Throwable failure = result.exception();
		if (failure == null) {
			return OK;
		}
		if (failure instanceof RpcDeadlineExceededException && failure.getCause() instanceof TimeoutException) {
			return TIMEOUT;
		}
		if (failure instanceof RpcException rpc) {
			return rpc.status().name();
		}
		return Status.BIZ_EXCEPTION.name();
	}

	static String server(Invocation invocation, Result result, Throwable error) {
		if (error != null) {
			Throwable failure = unwrap(error);
			return failure instanceof RpcException rpc && !invocation.targetInvoked() ? rpc.status().name() : Status.SERVER_ERROR.name();
		}
		if (result == null) {
			return Status.SERVER_ERROR.name();
		}
		Throwable failure = result.exception();
		if (failure == null) {
			return OK;
		}
		if (failure instanceof Error) {
			return Status.SERVER_ERROR.name();
		}
		if (failure instanceof RpcException rpc) {
			return invocation.targetInvoked() ? Status.SERVER_ERROR.name() : rpc.status().name();
		}
		return Status.BIZ_EXCEPTION.name();
	}

	private static Throwable unwrap(Throwable error) {
		Throwable current = error;
		while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}
}
