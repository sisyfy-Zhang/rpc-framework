package com.feynman.rpc.core.filter;

import java.util.concurrent.CompletableFuture;

/** Filter 链上"下一跳"的抽象：可能是下一个 Filter，也可能是链尾的真正调用（客户端发送 / 服务端反射）。 */
@FunctionalInterface
public interface Invoker {

	CompletableFuture<Result> invoke(Invocation invocation);
}
