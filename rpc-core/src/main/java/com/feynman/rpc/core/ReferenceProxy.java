package com.feynman.rpc.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import com.feynman.rpc.core.filter.Invocation;
import com.feynman.rpc.core.internal.InFlightGate;
import com.feynman.rpc.core.server.ServiceDispatcher;
import com.feynman.rpc.core.filter.Invoker;
import com.feynman.rpc.core.filter.Result;

/**
 * 远程服务代理的调用处理器。用 JDK {@link java.lang.reflect.Proxy} 而不是 CGLIB：被引用的一定是接口，
 * 不需要生成子类，也就不需要 {@code --add-opens} 去满足 CGLIB 的反射需求。
 *
 * <p>异常交还规则（同步方法）：
 * <ul>
 *   <li>服务端异常已在 RpcClient 还原（同类型或 {@link RpcRemoteException}）：非受检异常原样抛出；</li>
 *   <li>受检异常若在方法 {@code throws} 中声明则原样抛出——调用方的 catch 与本地调用写法一致；</li>
 *   <li>未声明的受检异常不能从代理里抛（JDK 会包成 UndeclaredThrowableException），包成 {@link RpcException}，cause 为原异常；</li>
 *   <li>传输失败 / 超时：{@link RpcException} / {@link RpcDeadlineExceededException}。</li>
 * </ul>
 * 返回 {@code CompletableFuture<T>}（或 {@code CompletionStage<T>}）的方法：future 以同样的异常对象异常完成（不再包 CompletionException）。
 *
 * <p>停机：每次调用先过所属客户端的在途闸门（{@link InFlightGate}），客户端 close() 关闸之后新调用直接抛
 * {@link IllegalStateException}——这是调用方的使用错误（用了已关闭的客户端），不是一次远程失败；已经发出的调用
 * 计入在途，close() 会等它们完成。
 *
 * <p>deadline 继承：{@code inheritDeadline} 打开时（默认），本次调用的超时取引用配置的超时与
 * {@link RpcContext} 上可继承的截止时间（服务端业务方法的入站 deadline、或 {@code withDeadline} 作用域）的较小者。
 */
final class ReferenceProxy implements InvocationHandler {

	private final ServiceKey key;
	private final Invoker chain;
	private final Duration timeout;
	private final boolean inheritDeadline;
	private final InFlightGate gate;

	ReferenceProxy(ServiceKey key, Invoker chain, Duration timeout, boolean inheritDeadline, InFlightGate gate) {
		this.key = key;
		this.chain = chain;
		this.timeout = timeout;
		this.inheritDeadline = inheritDeadline;
		this.gate = gate;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		// equals/hashCode/toString 走本地实现，不要把它们当成远程调用发出去
		if (method.getDeclaringClass() == Object.class) {
			return switch (method.getName()) {
				case "toString" -> "RpcReference(" + key + ")";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> args != null && args.length == 1 && args[0] == proxy;
				default -> throw new UnsupportedOperationException(method.toString());
			};
		}

		if (!gate.tryEnter()) {
			throw new IllegalStateException("RpcClient is closed; cannot call " + key + "#" + method.getName());
		}
		Invocation invocation;
		CompletableFuture<Result> future;
		try {
			invocation = new Invocation(key, method, args, RpcContext.consumeAttachments(), effectiveTimeout());
			try {
				future = chain.invoke(invocation);
			} catch (Throwable t) {
				future = CompletableFuture.failedFuture(t);
			}
		} catch (Throwable t) {
			gate.exit();
			throw t;
		}
		future.whenComplete((result, error) -> gate.exit());

		if (ServiceDispatcher.isAsync(method)) {
			// CompletableFuture 本身就是 CompletionStage：声明为任一种都直接交还这个 future
			CompletableFuture<Object> out = new CompletableFuture<>();
			future.whenComplete((result, error) -> {
				if (error != null) {
					out.completeExceptionally(translate(unwrap(error), invocation));
				} else if (result.hasException()) {
					out.completeExceptionally(result.exception());
				} else {
					out.complete(result.value());
				}
			});
			return out;
		}

		Result result;
		try {
			result = future.join();
		} catch (CompletionException e) {
			throw translate(unwrap(e), invocation);
		}
		if (result.hasException()) {
			throw throwable(result.exception(), method);
		}
		Object value = result.value();
		if (value == null && method.getReturnType().isPrimitive() && method.getReturnType() != void.class) {
			throw new RpcException("Remote method returned null for primitive return type " + method.getReturnType() + ": " + invocation);
		}
		return value;
	}

	/**
	 * 引用超时与可继承截止时间的较小者。继承来的预算已经耗尽时给 1ns：调用会在发送前以
	 * RpcDeadlineExceededException 结束（Duration.ZERO 在 Invocation 里表示"不限"，不能用）。
	 */
	private Duration effectiveTimeout() {
		if (!inheritDeadline) {
			return timeout;
		}
		Deadline inherited = RpcContext.inheritedDeadline();
		if (inherited == null) {
			return timeout;
		}
		long remaining = inherited.remainingNanos();
		if (remaining >= timeout.toNanos()) {
			return timeout;
		}
		return Duration.ofNanos(Math.max(1, remaining));
	}

	/** 决定把服务端异常以什么形态从同步代理里抛出（见类注释）。 */
	static Throwable throwable(Throwable remote, Method method) {
		if (remote instanceof RuntimeException || remote instanceof Error) {
			return remote;
		}
		for (Class<?> declared : method.getExceptionTypes()) {
			if (declared.isInstance(remote)) {
				return remote;
			}
		}
		return new RpcException("Remote method threw undeclared checked exception " + remote.getClass().getName()
				+ ": " + remote.getMessage(), remote);
	}

	private static Throwable unwrap(Throwable error) {
		return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
	}

	private static RuntimeException translate(Throwable cause, Invocation invocation) {
		if (cause instanceof TimeoutException) {
			return new RpcDeadlineExceededException("RPC call timed out after " + invocation.timeout().toMillis() + "ms: " + invocation, cause);
		}
		if (cause instanceof RpcException rpcException) {
			return rpcException;
		}
		return new RpcException("RPC call failed: " + invocation, cause);
	}
}
