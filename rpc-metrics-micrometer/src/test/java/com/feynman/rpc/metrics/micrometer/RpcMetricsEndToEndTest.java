package com.feynman.rpc.metrics.micrometer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.config.ReferenceOptions;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 真实的 RpcServer / RpcClient 往返（fastjson2、本机直连）：三条路径（成功 / 业务异常 / 传输失败）在两端各自打到正确的
 * status tag 且计时非零；在途与连接数 Gauge 跟随实际状态，close 之后归零，解绑后从 registry 消失，且不阻止客户端被回收。
 */
class RpcMetricsEndToEndTest {

	public interface Inventory {
		String reserve(String sku);

		String slow(Integer millis);
	}

	public static final class OutOfStockException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public OutOfStockException(String message) {
			super(message);
		}
	}

	static final class InventoryImpl implements Inventory {
		final CountDownLatch slowStarted = new CountDownLatch(1);

		@Override
		public String reserve(String sku) {
			if (sku.startsWith("none")) {
				throw new OutOfStockException("no stock for " + sku);
			}
			return "reserved " + sku;
		}

		@Override
		public String slow(Integer millis) {
			slowStarted.countDown();
			try {
				Thread.sleep(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "slept " + millis;
		}
	}

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private RpcServer server;
	private RpcClient client;

	@AfterEach
	void cleanup() {
		if (client != null) {
			client.close();
		}
		if (server != null) {
			server.close();
		}
	}

	private RpcServer startServer(InventoryImpl impl) {
		RpcServer s = RpcServer.builder().port(0).host("127.0.0.1").serializer("fastjson2")
				.serverFilter(new MicrometerServerFilter(registry)).build();
		s.export(Inventory.class, impl);
		s.start();
		return s;
	}

	private RpcClient connect(int port) {
		return RpcClient.builder().registry(StaticRegistryFactory.config(port)).serializer("fastjson2")
				.exceptionAllowlist(OutOfStockException.class.getName())
				.clientFilter(new MicrometerClientFilter(registry)).build();
	}

	private Timer timer(String name, String method, String status) {
		return registry.find(name).tag("service", Inventory.class.getName()).tag("method", method).tag("status", status).timer();
	}

	@Test
	void successBusinessExceptionAndTransportFailureLandOnTheirStatusTags() {
		server = startServer(new InventoryImpl());
		client = connect(server.port());
		Inventory inventory = client.reference(Inventory.class, ReferenceOptions.defaults().timeout(Duration.ofSeconds(5)));

		assertEquals("reserved a-1", inventory.reserve("a-1"));
		assertThrows(OutOfStockException.class, () -> inventory.reserve("none-1"));

		for (String side : new String[] { MicrometerClientFilter.METER_NAME, MicrometerServerFilter.METER_NAME }) {
			Timer ok = timer(side, "reserve", "OK");
			Timer biz = timer(side, "reserve", "BIZ_EXCEPTION");
			assertNotNull(ok, side + " OK: " + registry.getMetersAsString());
			assertNotNull(biz, side + " BIZ_EXCEPTION: " + registry.getMetersAsString());
			assertEquals(1, ok.count());
			assertEquals(1, biz.count());
			assertTrue(ok.totalTime(TimeUnit.NANOSECONDS) > 0 && biz.totalTime(TimeUnit.NANOSECONDS) > 0, side + " timed");
		}

		// 传输失败：提供者进程没了（端口关闭），调用连不上
		server.close();
		server = null;
		await().atMost(5, TimeUnit.SECONDS).until(() -> client.activeConnections() == 0);
		assertThrows(RpcException.class, () -> inventory.reserve("a-2"));
		Timer failed = timer(MicrometerClientFilter.METER_NAME, "reserve", "TRANSPORT_FAILURE");
		assertNotNull(failed, registry.getMetersAsString());
		assertEquals(1, failed.count());
		assertTrue(failed.totalTime(TimeUnit.NANOSECONDS) > 0);
		assertNull(timer(MicrometerServerFilter.METER_NAME, "reserve", "TRANSPORT_FAILURE"), "the server never saw it");
	}

	@Test
	void gaugesFollowInFlightAndConnectionsAndDropToZeroAfterClose() throws Exception {
		InventoryImpl impl = new InventoryImpl();
		server = startServer(impl);
		client = connect(server.port());
		RpcServerMetrics serverMetrics = RpcServerMetrics.bind(server, registry);
		RpcClientMetrics clientMetrics = RpcClientMetrics.bind(client, registry);
		Inventory inventory = client.reference(Inventory.class, ReferenceOptions.defaults().timeout(Duration.ofSeconds(5)));
		assertEquals("reserved warm", inventory.reserve("warm"));

		CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> inventory.slow(500));
		assertTrue(impl.slowStarted.await(5, TimeUnit.SECONDS));
		assertEquals(1.0, gauge(RpcClientMetrics.INFLIGHT));
		assertEquals(1.0, gauge(RpcServerMetrics.INFLIGHT));
		assertTrue(gauge(RpcClientMetrics.CONNECTIONS) >= 1.0);
		await().atMost(5, TimeUnit.SECONDS).until(() -> gauge(RpcServerMetrics.CONNECTIONS) == gauge(RpcClientMetrics.CONNECTIONS));
		assertEquals("slept 500", slow.get(5, TimeUnit.SECONDS));
		await().atMost(5, TimeUnit.SECONDS).until(() -> gauge(RpcClientMetrics.INFLIGHT) == 0.0 && gauge(RpcServerMetrics.INFLIGHT) == 0.0);

		client.close();
		assertEquals(0.0, gauge(RpcClientMetrics.INFLIGHT));
		assertEquals(0.0, gauge(RpcClientMetrics.CONNECTIONS), "closed client: no connections");
		await().atMost(5, TimeUnit.SECONDS).until(() -> gauge(RpcServerMetrics.CONNECTIONS) == 0.0);
		server.close();
		assertEquals(0.0, gauge(RpcServerMetrics.INFLIGHT));
		assertEquals(0.0, gauge(RpcServerMetrics.CONNECTIONS));

		clientMetrics.close();
		serverMetrics.close();
		assertNull(registry.find(RpcClientMetrics.INFLIGHT).gauge(), "unbound gauges leave the registry");
		assertNull(registry.find(RpcServerMetrics.CONNECTIONS).gauge());
		clientMetrics.close(); // 可重复调用
	}

	@Test
	void gaugesHoldTheClientWeaklySoAnUnusedClientCanBeCollected() {
		SimpleMeterRegistry local = new SimpleMeterRegistry();
		RpcClient doomed = RpcClient.builder().registry(StaticRegistryFactory.config(1)).serializer("fastjson2").build();
		RpcClientMetrics.bind(doomed, local, Tags.of("client", "doomed"));
		doomed.close();
		WeakReference<RpcClient> ref = new WeakReference<>(doomed);
		doomed = null;
		Gauge gauge = local.find(RpcClientMetrics.INFLIGHT).tag("client", "doomed").gauge();
		assertNotNull(gauge);
		await().atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(100)).until(() -> {
			System.gc();
			return ref.get() == null;
		});
		assertTrue(Double.isNaN(gauge.value()), "a collected client reads NaN instead of being kept alive by the registry");
	}

	private double gauge(String name) {
		Gauge gauge = registry.find(name).gauge();
		assertNotNull(gauge, name + " not bound");
		return gauge.value();
	}
}
