package com.feynman.rpc.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

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
 * <p>同步方法的等待有两道上界：第一道是在途表的 deadline 定时器（超时后 future 以超时完成，正常情况总是它先到）；
 * 第二道是这里的有界等待——最多等"本次调用 deadline 的剩余时间 + {@code syncWaitGrace}"（{@code RpcClientConfig}，
 * 默认 1s）。定时器没有按时触发时（运行时内存事故等），调用方线程不会无限期挂住：
 * 抛 {@link RpcDeadlineExceededException}（"deadline timer did not fire within grace"），并让本次调用已发出的请求失败
 * （在途表、在途闸门、连接上的请求记录随之释放）。等待不响应中断，与此前的 {@code join()} 一致（中断标志保留）。
 * <b>异步方法不受第二道上界约束</b>：返回的 future 只由定时器 / 响应完成，调用方应自带 {@code orTimeout} 之类的上界。
 *
 * <p>停机：每次调用先过所属客户端的在途闸门（{@link InFlightGate}），客户端 close() 关闸之后新调用直接抛
 * {@link IllegalStateException}——这是调用方的使用错误（用了已关闭的客户端），不是一次远程失败；已经发出的调用
 * 计入在途，close() 会等它们完成。
 *
 * <p><b>不能在本客户端的 IO 事件循环线程上调用同步方法</b>：异步方法返回的 future 的回调（{@code thenApply} 等）
 * 缺省就在这些线程上执行，在里面再调同步方法会阻塞事件循环——这条事件循环上所有连接停摆，嵌套调用的响应若恰好也由它读取则永远读不到，
 * 只能等到超时。同步入口检测到这种情况直接抛 {@link IllegalStateException}（一次线程类型判断，普通线程上几乎零成本）；
 * 异步方法不受影响（不等待，可以在回调里链式发起）。
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
	private final long syncWaitGraceNanos;
	/** 当前线程是否是所属客户端的 IO 事件循环线程（RpcClient.inClientEventLoop）。 */
	private final BooleanSupplier inClientEventLoop;

	ReferenceProxy(ServiceKey key, Invoker chain, Duration timeout, boolean inheritDeadline, InFlightGate gate,
			Duration syncWaitGrace, BooleanSupplier inClientEventLoop) {
		this.key = key;
		this.chain = chain;
		this.timeout = timeout;
		this.inheritDeadline = inheritDeadline;
		this.gate = gate;
		this.syncWaitGraceNanos = syncWaitGrace.toNanos();
		this.inClientEventLoop = inClientEventLoop;
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

		boolean async = ServiceDispatcher.isAsync(method);
		if (!async && inClientEventLoop.getAsBoolean()) {
			// 在发出请求之前拒绝：此时什么都还没登记，没有需要回滚的状态
			throw new IllegalStateException("Synchronous RPC " + key + "#" + method.getName() + " called on this RpcClient's IO event-loop thread "
					+ Thread.currentThread().getName() + " (e.g. inside a callback of an async method's CompletableFuture). Blocking here would"
					+ " stall every connection on this event loop, and the nested reply could never be read. In an async callback, call the async"
					+ " variant of the method, or switch to your own executor first (thenApplyAsync / thenComposeAsync with an executor).");
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

		if (async) {
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
			result = awaitSync(future, invocation);
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
	 * 同步等待：最多等 deadline 剩余时间 + grace（见类注释）。不响应中断（与 {@code join()} 相同），中断标志在返回前恢复。
	 * 失败时抛 {@link CompletionException}（cause 为原因），由调用处统一翻译。
	 */
	private Result awaitSync(CompletableFuture<Result> future, Invocation invocation) {
		long remaining = invocation.remainingNanos();
		if (remaining == Long.MAX_VALUE) {
			// timeout = 0（不限）：没有 deadline 也就没有定时器，只能等结果
			return future.join();
		}
		long waitNanos = Math.max(0, remaining) + syncWaitGraceNanos;
		if (waitNanos < 0) {
			waitNanos = Long.MAX_VALUE;
		}
		long until = System.nanoTime() + waitNanos;
		boolean interrupted = false;
		try {
			while (true) {
				try {
					return future.get(waitNanos, TimeUnit.NANOSECONDS);
				} catch (ExecutionException e) {
					throw new CompletionException(e.getCause());
				} catch (InterruptedException e) {
					interrupted = true;
					waitNanos = until - System.nanoTime();
					if (waitNanos > 0) {
						continue;
					}
				} catch (TimeoutException e) {
					// 落到下面
				}
				return abandon(future, invocation);
			}
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * deadline + grace 已过、定时器仍没让 future 完成：让本次调用已发出的请求失败（在途表移除、连接与负载统计释放），
	 * 并直接让 future 失败（在途闸门随之退出）。两步之间若真实结果恰好到达，以 future 最终的状态为准。
	 */
	private Result abandon(CompletableFuture<Result> future, Invocation invocation) {
		RpcDeadlineExceededException cause = new RpcDeadlineExceededException("RPC call exceeded its deadline of "
				+ invocation.timeout().toMillis() + "ms and the deadline timer did not fire within grace "
				+ TimeUnit.NANOSECONDS.toMillis(syncWaitGraceNanos) + "ms: " + invocation);
		invocation.abandon(cause);
		future.completeExceptionally(cause);
		return future.join();
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
