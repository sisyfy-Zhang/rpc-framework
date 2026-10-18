package com.feynman.rpc.core.filter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.ServiceKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilterChainTest {

	private static Invocation invocation() throws NoSuchMethodException {
		return new Invocation(ServiceKey.of(Runnable.class), Runnable.class.getMethod("run"), null, Map.of("k", "v"), Duration.ofSeconds(1));
	}

	private static Filter recording(String name, List<String> trace) {
		return (next, invocation) -> {
			trace.add(name + ">");
			return next.invoke(invocation).whenComplete((r, e) -> trace.add("<" + name));
		};
	}

	@Test
	void listOrderIsExecutionOrder() throws Exception {
		List<String> trace = new ArrayList<>();
		Invoker chain = FilterChain.build(List.of(recording("A", trace), recording("B", trace)), invocation -> {
			trace.add("terminal");
			return CompletableFuture.completedFuture(Result.value("done"));
		});

		Result result = chain.invoke(invocation()).join();

		assertEquals("done", result.value());
		assertEquals(List.of("A>", "B>", "terminal", "<B", "<A"), trace);
	}

	@Test
	void filterCanShortCircuitWithoutCallingNext() throws Exception {
		Invoker chain = FilterChain.build(List.of((next, inv) -> CompletableFuture.completedFuture(Result.value("rejected"))),
				invocation -> {
					throw new AssertionError("terminal must not be reached");
				});

		assertEquals("rejected", chain.invoke(invocation()).join().value());
	}

	@Test
	void filterSeesAndCanAddAttachments() throws Exception {
		List<String> seen = new ArrayList<>();
		Filter addTrace = (next, inv) -> {
			inv.setAttachment("traceparent", "00-abc");
			return next.invoke(inv);
		};
		Invoker chain = FilterChain.build(List.of(addTrace), inv -> {
			seen.add(inv.getAttachment("k") + "," + inv.getAttachment("traceparent"));
			return CompletableFuture.completedFuture(Result.value(null));
		});

		chain.invoke(invocation()).join();

		assertEquals(List.of("v,00-abc"), seen);
	}

	@Test
	void synchronousFilterExceptionBecomesFailedFuture() throws Exception {
		Invoker chain = FilterChain.build(List.of((next, inv) -> {
			throw new IllegalStateException("filter bug");
		}), inv -> CompletableFuture.completedFuture(Result.value(null)));

		CompletableFuture<Result> future = chain.invoke(invocation());
		CompletionException e = assertThrows(CompletionException.class, future::join);
		assertInstanceOf(IllegalStateException.class, e.getCause());
	}

	private static Filter ordered(String name, int order, List<String> trace) {
		return new Filter() {
			@Override
			public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
				trace.add(name);
				return next.invoke(invocation);
			}

			@Override
			public int order() {
				return order;
			}
		};
	}

	@Test
	void orderSortsStablyAndBuiltInsRunFirst() throws Exception {
		List<String> trace = new ArrayList<>();
		// 用户 Filter 默认 order 0；内置 Filter 用负值；同 order 保持注册顺序
		Invoker chain = FilterChain.build(List.of(ordered("user-1", 0, trace), ordered("deadline", -2000, trace),
				ordered("user-2", 0, trace), ordered("tracing", -3000, trace), ordered("late", 10, trace)),
				invocation -> CompletableFuture.completedFuture(Result.value(null)));

		chain.invoke(invocation()).join();

		assertEquals(List.of("tracing", "deadline", "user-1", "user-2", "late"), trace);
	}

	@Test
	void deadlineClientFilterWritesRelativeAndAbsoluteDeadline() throws Exception {
		List<String> seen = new ArrayList<>();
		Invoker chain = FilterChain.build(List.of(new DeadlineClientFilter()), inv -> {
			seen.add(inv.getAttachment("rpc.timeout"));
			seen.add(inv.getAttachment("rpc.deadline"));
			return CompletableFuture.completedFuture(Result.value(null));
		});
		long before = System.currentTimeMillis();
		chain.invoke(invocation()).join(); // timeout = 1s

		long timeout = Long.parseLong(seen.get(0));
		long deadline = Long.parseLong(seen.get(1));
		org.junit.jupiter.api.Assertions.assertTrue(timeout > 900 && timeout <= 1000, "rpc.timeout=" + timeout);
		org.junit.jupiter.api.Assertions.assertTrue(deadline >= before + 900 && deadline <= System.currentTimeMillis() + 1000,
				"rpc.deadline=" + deadline);
	}

	@Test
	void deadlineServerFilterRejectsExpiredRequestsWithoutCallingNext() throws Exception {
		java.util.concurrent.atomic.AtomicInteger executed = new java.util.concurrent.atomic.AtomicInteger();
		Invoker chain = FilterChain.build(List.of(new DeadlineServerFilter()), inv -> {
			executed.incrementAndGet();
			return CompletableFuture.completedFuture(Result.value("ran"));
		});
		long arrivedLongAgo = System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500);
		Invocation expired = Invocation.inbound(ServiceKey.of(Runnable.class), Runnable.class.getMethod("run"),
				() -> new Object[0], Map.of("rpc.timeout", "100"), Duration.ofMillis(100), arrivedLongAgo);
		Invocation fresh = Invocation.inbound(ServiceKey.of(Runnable.class), Runnable.class.getMethod("run"),
				() -> new Object[0], Map.of("rpc.timeout", "10000"), Duration.ofMillis(10000), System.nanoTime());

		Result rejected = chain.invoke(expired).join();
		assertInstanceOf(com.feynman.rpc.core.RpcDeadlineExceededException.class, rejected.exception());
		assertEquals(0, executed.get());
		assertEquals("ran", chain.invoke(fresh).join().value());
		assertEquals(1, executed.get());
	}
}
