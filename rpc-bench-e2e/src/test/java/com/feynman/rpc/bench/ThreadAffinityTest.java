package com.feynman.rpc.bench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

/**
 * {@code --pin-dispatcher} 的平台门控：任何平台上都不抛、结果如实记录。Linux 上真的钉一次（找名为
 * {@value ThreadAffinity#DISPATCHER_THREAD_NAME} 的线程、读回亲和），测完把整个测试 JVM 的亲和恢复原样；
 * 其它平台记 {@code unsupported}。不断言一定钉成功：CI 容器可能不给 taskset、或只给一个核。
 */
class ThreadAffinityTest {

	@Test
	void pinNeverThrowsAndRecordsWhatHappened() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		Thread dispatcher = Thread.ofPlatform().name(ThreadAffinity.DISPATCHER_THREAD_NAME).start(() -> {
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			int cpu = ThreadAffinity.supported() ? ThreadAffinity.processCpus().first() : 0;
			Map<String, Object> result = assertDoesNotThrow(() -> ThreadAffinity.pin(ThreadAffinity.DISPATCHER_THREAD_NAME, cpu));
			System.out.println("[thread affinity] " + result);
			assertNotNull(result.get("status"), "the outcome is recorded: " + result);
			assertEquals(cpu, result.get("cpu"));
			if (!ThreadAffinity.supported()) {
				assertEquals("unsupported", result.get("status"));
			} else {
				assertTrue(Set.of("pinned", "partial", "failed", "skipped").contains(result.get("status")), result.toString());
				if ("pinned".equals(result.get("status"))) {
					assertEquals(String.valueOf(cpu), result.get("dispatcher_cpus_allowed"));
					List<String> strays = ThreadAffinity.strays(cpu, (Long) result.get("dispatcher_tid"));
					assertNotNull(strays);
				}
			}
		} finally {
			release.countDown();
			dispatcher.join();
			assertTrue(ThreadAffinity.unpinAll() <= 0 || !ThreadAffinity.supported(), "test JVM affinity restored");
		}
	}

	@Test
	void unsupportedPlatformsReportNullStrays() {
		if (!ThreadAffinity.supported()) {
			assertEquals(null, ThreadAffinity.strays(0, 1));
			assertEquals(0, ThreadAffinity.unpinAll());
		}
	}

	@Test
	void cpuListsFormatLikeTaskset() {
		assertEquals("0-2,5", ThreadAffinity.format(new TreeSet<>(List.of(0, 1, 2, 5))));
		assertEquals("6-9", ThreadAffinity.format(new TreeSet<>(List.of(6, 7, 8, 9))));
		assertEquals("3", ThreadAffinity.format(new TreeSet<>(List.of(3))));
		assertEquals("1,3,5-6", ThreadAffinity.format(new TreeSet<>(List.of(1, 3, 5, 6))));
		assertEquals("", ThreadAffinity.format(new TreeSet<>()));
	}

	@Test
	void theDispatcherNameFitsInComm() {
		assertTrue(ThreadAffinity.DISPATCHER_THREAD_NAME.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 15,
				"Linux truncates comm to 15 bytes; the dispatcher must be findable by its full name");
	}
}
