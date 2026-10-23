package io.github.sisyfyzhang.steadyrpc.core.filter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 把 Filter 列表组装成一个 {@link Invoker}。先按 {@link Filter#order()} 稳定排序（同 order 保持列表顺序），
 * 再从末尾开始由内向外包装，因此<b>排序后的顺序即执行顺序</b>：{@code [A, B]} 的执行路径是
 * A → B → terminal → B 的后处理 → A 的后处理。
 *
 * <p>链在 RpcClient.reference / RpcServer.export 时组装一次并复用，每次调用不再分配包装对象。
 */
public final class FilterChain {

	private FilterChain() {
	}

	public static Invoker build(List<? extends Filter> filters, Invoker terminal) {
		List<Filter> ordered = ordered(filters);
		Invoker current = terminal;
		for (int i = ordered.size() - 1; i >= 0; i--) {
			Filter filter = ordered.get(i);
			Invoker next = current;
			current = invocation -> invokeSafely(filter, next, invocation);
		}
		return current;
	}

	/** 按 order 稳定排序后的副本。 */
	public static List<Filter> ordered(List<? extends Filter> filters) {
		List<Filter> copy = new ArrayList<>(filters);
		copy.sort(Comparator.comparingInt(Filter::order)); // List.sort 是稳定排序
		return copy;
	}

	/** Filter 同步抛出的异常也转成失败的 future：调用方只需要处理一种失败形态。 */
	private static CompletableFuture<Result> invokeSafely(Filter filter, Invoker next, Invocation invocation) {
		try {
			CompletableFuture<Result> result = filter.invoke(next, invocation);
			if (result == null) {
				return CompletableFuture.failedFuture(
						new IllegalStateException("Filter returned null future: " + filter.getClass().getName()));
			}
			return result;
		} catch (Throwable t) {
			return CompletableFuture.failedFuture(t);
		}
	}
}
