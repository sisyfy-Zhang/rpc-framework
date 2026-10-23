package io.github.sisyfyzhang.steadyrpc.core;

import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;

/**
 * 框架层面的调用失败（无可用节点、超时、连接失败、服务端返回非 OK 状态）。非受检异常：
 * 远程接口的方法签名不应该被迫声明框架异常，这和 gRPC 的 StatusRuntimeException、
 * Dubbo 的 RpcException 是同一个取舍。
 *
 * <p><b>状态码随异常走</b>（{@link #status()}）：服务端 Filter 想拒绝一个请求时，返回带对应异常的 Result 即可，
 * 分发器按 {@code status()} 决定回包状态，不再维护一条 {@code instanceof} 链——新增一种拒绝（例如并发限制回
 * SERVER_OVERLOADED）只需要一个新的子类，而不是去改分发器。客户端还原出的异常也带着它，调用方可以按状态分支。
 */
public class RpcException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public RpcException(String message) {
		super(message);
	}

	public RpcException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * 这类失败对应的协议状态。基类是 {@link Status#SERVER_ERROR}：没有更具体分类的框架失败，从服务端视角都是
	 * "服务端没能完成"。子类覆盖为 DEADLINE_EXCEEDED / SERVER_OVERLOADED / SERIALIZATION_ERROR 等。
	 *
	 * <p>服务端只在请求<b>被拒绝、业务方法没有执行</b>时按它回包；业务方法自己抛出的 RpcException（典型是它发起的
	 * 下游调用失败了）一律按 SERVER_ERROR 回包——否则下游的 SERVER_OVERLOADED 会被上游当成"可安全换节点重试"，
	 * 而业务方法其实已经执行过了。
	 */
	public Status status() {
		return Status.SERVER_ERROR;
	}
}
