package com.feynman.rpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class HarnessOptionsTest {

	private static final Profile HTTP_OPEN = ProfileLoader.parse(
			DispatcherDelayedTest.openCard("it-options", "rate: 100", "2s", "500ms", "[500ms, 2s]", "1s"), null, "test");

	@Test
	void noOptionsMeansTheDefaults() {
		HarnessOptions options = HarnessOptions.parse(null, null, null, null);
		assertEquals(HarnessOptions.DEFAULTS, options);
		assertEquals(Duration.ofMillis(500), options.effectivePreWarmup(HTTP_OPEN), "pre-warmup defaults to the card's warmup");
		Map<String, Object> json = options.toJson(HTTP_OPEN);
		assertEquals(500.0, ((Number) json.get("pre_warmup_ms")).doubleValue());
		assertEquals("load.warmup", json.get("pre_warmup_source"));
		// http 开环卡的缺省：阻塞客户端 + 平台线程工作者
		assertEquals(HarnessOptions.HTTP_CLIENT_BLOCKING, json.get("http_client"));
		assertEquals(HarnessOptions.WORKERS_POOL_PLATFORM, json.get("open_workers"));
		assertEquals(HarnessOptions.WORKERS_POOL, HarnessOptions.parse(null, HarnessOptions.HTTP_CLIENT_NETTY, null, null, null, null)
				.effectiveOpenWorkers(HTTP_OPEN), "netty client keeps virtual-thread workers");
	}

	@Test
	void parsesEveryForm() {
		HarnessOptions options = HarnessOptions.parse("pool:8", "2", "5", "3s");
		assertTrue(options.pool(HTTP_OPEN));
		assertEquals(8, options.effectivePoolSize(HTTP_OPEN));
		assertEquals(2, options.ioThreads());
		assertEquals(5, options.pinDispatcher());
		assertEquals(Duration.ofSeconds(3), options.effectivePreWarmup(HTTP_OPEN));

		HarnessOptions pool = HarnessOptions.parse("pool", null, "off", "0");
		assertEquals(4, pool.effectivePoolSize(HTTP_OPEN), "http cards: one worker per connection (target.http.connections)");
		assertNull(pool.pinDispatcher());
		assertTrue(pool.effectivePreWarmup(HTTP_OPEN).isZero());
		assertEquals("off", pool.toJson(HTTP_OPEN).get("pin_dispatcher"));

		assertEquals(HarnessOptions.WORKERS_PER_REQUEST, HarnessOptions.parse("per-request", null, "none", null).openWorkers());

		HarnessOptions platform = HarnessOptions.parse("pool-platform:16", "netty", "1", "on", null, null);
		assertTrue(platform.pool(HTTP_OPEN));
		assertTrue(platform.platformWorkers(HTTP_OPEN));
		assertEquals(16, platform.effectivePoolSize(HTTP_OPEN));
		assertTrue(platform.ioBusyPoll());
		assertEquals(Boolean.TRUE, platform.toJson(HTTP_OPEN).get("io_busy_poll"));
		assertEquals(false, HarnessOptions.parse(null, null, "off", null, null).ioBusyPoll());
	}

	@Test
	void rejectsNonsense() {
		assertThrows(IllegalArgumentException.class, () -> HarnessOptions.parse("threads", null, null, null));
		assertThrows(IllegalArgumentException.class, () -> HarnessOptions.parse("pool:0", null, null, null));
		assertThrows(IllegalArgumentException.class, () -> HarnessOptions.parse(null, "0", null, null));
		assertThrows(IllegalArgumentException.class, () -> HarnessOptions.parse(null, "two", null, null));
		assertThrows(IllegalArgumentException.class, () -> HarnessOptions.parse(null, null, "-1", null));
		assertThrows(IllegalArgumentException.class, () -> HarnessOptions.parse(null, null, null, "20"), "bare numbers other than 0 need a unit");
		assertThrows(IllegalArgumentException.class, () -> HarnessOptions.parse(null, null, "yes", null, null));
	}
}
