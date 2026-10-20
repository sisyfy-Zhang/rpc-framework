package com.feynman.rpc.core.filter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.ServiceKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link Invocation#onAbandon} / {@link Invocation#abandon}：同步代理放弃等待时让已发出的请求失败的通道。 */
class InvocationAbandonTest {

	private static Invocation invocation() throws Exception {
		return new Invocation(ServiceKey.of(Runnable.class), Runnable.class.getMethod("run"), null, Map.of(), Duration.ofSeconds(1));
	}

	@Test
	void everyRegisteredHookRunsOnceWithTheCause() throws Exception {
		Invocation invocation = invocation();
		List<Throwable> seen = new ArrayList<>();
		invocation.onAbandon(seen::add); // 第一次尝试
		invocation.onAbandon(seen::add); // 重试
		invocation.onAbandon(seen::add);
		RuntimeException cause = new RuntimeException("gave up");
		invocation.abandon(cause);
		invocation.abandon(new RuntimeException("second abandon is ignored"));
		assertEquals(3, seen.size());
		seen.forEach(t -> assertSame(cause, t));
	}

	@Test
	void aHookRegisteredAfterAbandonRunsImmediately() throws Exception {
		Invocation invocation = invocation();
		RuntimeException cause = new RuntimeException("gave up");
		invocation.abandon(cause);
		List<Throwable> seen = new ArrayList<>();
		invocation.onAbandon(seen::add); // 迟到的重试：立即失败，不再等
		assertEquals(List.of(cause), seen);
	}

	@Test
	void abandonWithoutHooksIsANoOp() throws Exception {
		invocation().abandon(new RuntimeException("nothing in flight"));
	}
}
