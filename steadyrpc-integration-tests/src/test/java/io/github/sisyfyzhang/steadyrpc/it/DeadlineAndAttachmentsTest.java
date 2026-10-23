package io.github.sisyfyzhang.steadyrpc.it;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.RpcClient;
import io.github.sisyfyzhang.steadyrpc.core.RpcContext;
import io.github.sisyfyzhang.steadyrpc.core.RpcDeadlineExceededException;
import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.ReferenceOptions;
import io.github.sisyfyzhang.steadyrpc.core.filter.ClientFilter;
import io.github.sisyfyzhang.steadyrpc.core.filter.DeadlineServerFilter;
import io.github.sisyfyzhang.steadyrpc.core.filter.Filter;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invocation;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invoker;
import io.github.sisyfyzhang.steadyrpc.core.filter.Result;
import io.github.sisyfyzhang.steadyrpc.core.filter.ServerFilter;
import io.github.sisyfyzhang.steadyrpc.core.protocol.AttachmentKeys;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A0-6（进程内替代 toxiproxy）：用单线程业务池 + 一个占住线程 500ms 的慢调用，让第二个请求在服务端队列里排到过期。
 * <ul>
 *   <li>过期请求的业务方法<b>从未执行</b>（计数器为 0），客户端得到 {@link RpcDeadlineExceededException}；</li>
 *   <li>Cluster 不重试：failover + retries=2 下服务端只收到一次该请求、只产生一次 DEADLINE_EXCEEDED；</li>
 *   <li>服务端 DEADLINE_EXCEEDED 回包本身也映射为 RpcDeadlineExceededException（另一个用例让客户端超时足够长，
 *       由服务端先判定过期）；</li>
 *   <li>attachments 往返：客户端 RpcContext → 服务端 {@link RpcContext#serverAttachments()}；服务端 Filter 写的响应
 *       attachments → 客户端 Filter 看到的 Result。</li>
 * </ul>
 * 计数用一个 order 小于 {@link DeadlineServerFilter#ORDER} 的服务端 Filter：它排在内置 Deadline 检查之外，
 * 能看到每一个到达的请求和 Deadline 检查给出的结果。
 */
class DeadlineAndAttachmentsTest {

	public interface SlowService {
		String slow(long millis);

		String fast(String tag);

		String whoAmI();
	}

	static final class SlowServiceImpl implements SlowService {
		final AtomicInteger fastExecutions = new AtomicInteger();
		/** 每次占位前由 occupyTheOnlyWorker 重新装上：预热调用也会走 slow()，一次性的门闩会被它提前打开。 */
		volatile CountDownLatch slowStarted = new CountDownLatch(1);

		@Override
		public String slow(long millis) {
			slowStarted.countDown();
			try {
				Thread.sleep(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "slow-done";
		}

		@Override
		public String fast(String tag) {
			fastExecutions.incrementAndGet();
			return "fast:" + tag;
		}

		@Override
		public String whoAmI() {
			Map<String, String> inbound = RpcContext.serverAttachments();
			return inbound.get("tenant") + "|" + inbound.containsKey(AttachmentKeys.TIMEOUT) + "|" + inbound.containsKey(AttachmentKeys.DEADLINE);
		}
	}

	/** 排在内置 Deadline 检查之外的计数 Filter：统计 fast 的到达次数与被判过期的次数。 */
	static final class CountingFilter implements ServerFilter {
		final AtomicInteger fastArrivals = new AtomicInteger();
		final AtomicInteger fastDeadlineRejections = new AtomicInteger();
		volatile String overrideTimeoutForFast;

		@Override
		public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
			if (!"fast".equals(invocation.methodName())) {
				return next.invoke(invocation);
			}
			fastArrivals.incrementAndGet();
			if (overrideTimeoutForFast != null) {
				// 模拟"调用方给的剩余时间很短"：只改服务端看到的 rpc.timeout，客户端自己的超时保持很长
				invocation.setAttachment(AttachmentKeys.TIMEOUT, overrideTimeoutForFast);
			}
			return next.invoke(invocation).whenComplete((result, error) -> {
				if (result != null && result.exception() instanceof RpcDeadlineExceededException) {
					fastDeadlineRejections.incrementAndGet();
				}
			});
		}

		@Override
		public int order() {
			return DeadlineServerFilter.ORDER - 1000;
		}
	}

	private ZkFixture zk;
	private RpcServer server;
	private SlowServiceImpl impl;
	private CountingFilter counting;

	@BeforeEach
	void start() throws Exception {
		zk = new ZkFixture();
		impl = new SlowServiceImpl();
		counting = new CountingFilter();
		server = RpcServer.builder()
				.port(0).host("127.0.0.1").registry(zk.registry())
				.executor(ExecutorConfig.fixed(1))
				.serverFilter(counting)
				.build();
		server.export(SlowService.class, impl);
		server.start();
		server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
	}

	@AfterEach
	void stop() throws Exception {
		server.close();
		zk.close();
	}

	private RpcClient client(Filter... filters) {
		return RpcClient.builder()
				.registry(zk.registry())
				.cluster("failover")
				.defaultTimeout(Duration.ofSeconds(5))
				.clientFilter(filters)
				.build();
	}

	/** 占住服务端唯一的业务线程，返回慢调用的 future。 */
	private CompletableFuture<String> occupyTheOnlyWorker(SlowService service) throws InterruptedException {
		CountDownLatch started = new CountDownLatch(1);
		impl.slowStarted = started;
		CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> service.slow(500));
		assertTrue(started.await(5, TimeUnit.SECONDS), "slow call never started");
		return slow;
	}

	@Test
	void requestThatExpiresInTheQueueIsNeverExecutedAndNotRetried() throws Exception {
		try (RpcClient client = client()) {
			SlowService service = client.reference(SlowService.class);
			SlowService shortDeadline = client.reference(SlowService.class,
					ReferenceOptions.defaults().timeout(Duration.ofMillis(100)).retries(2));
			assertEquals("fast:warmup", service.fast("warmup")); // 建好连接、完成协商，排除首次建连耗时
			impl.fastExecutions.set(0);
			counting.fastArrivals.set(0);

			CompletableFuture<String> slow = occupyTheOnlyWorker(service);
			long start = System.nanoTime();
			RpcDeadlineExceededException e = assertThrows(RpcDeadlineExceededException.class, () -> shortDeadline.fast("late"));
			long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			System.out.println("[A0-6] client gave up after " + waitedMillis + "ms: " + e.getMessage());
			assertTrue(waitedMillis < 450, "the client must give up at its own deadline, not wait for the slow call: " + waitedMillis + "ms");

			assertEquals("slow-done", slow.get(5, TimeUnit.SECONDS));
			// 慢调用结束后，排队的过期请求才轮到：内置 Deadline 检查拒绝它
			await().atMost(5, TimeUnit.SECONDS).until(() -> counting.fastDeadlineRejections.get() == 1);
			Thread.sleep(200); // 给可能存在的重试留出到达的时间，再断言"只有一次"
			assertEquals(0, impl.fastExecutions.get(), "the expired request's business method must never run");
			assertEquals(1, counting.fastArrivals.get(), "Cluster must not retry a deadline-exceeded call (retries=2)");
			assertEquals(1, counting.fastDeadlineRejections.get(), "exactly one DEADLINE_EXCEEDED");
			assertEquals(0, client.pendingRequests());
		}
	}

	@Test
	void serverSideDeadlineExceededMapsToRpcDeadlineExceededException() throws Exception {
		counting.overrideTimeoutForFast = "50";
		try (RpcClient client = client()) {
			SlowService service = client.reference(SlowService.class);
			SlowService retrying = client.reference(SlowService.class, ReferenceOptions.defaults().retries(2));
			assertEquals("slow-done", service.slow(1)); // 预热连接
			impl.fastExecutions.set(0);

			CompletableFuture<String> slow = occupyTheOnlyWorker(service);
			RpcDeadlineExceededException e = assertThrows(RpcDeadlineExceededException.class, () -> retrying.fast("queued"));
			System.out.println("[A0-6] server answered: " + e.getMessage());
			assertTrue(e.getMessage().contains("server rejected expired request"), e.getMessage());
			assertEquals("slow-done", slow.get(5, TimeUnit.SECONDS));

			assertEquals(0, impl.fastExecutions.get(), "the business method must never run");
			assertEquals(1, counting.fastArrivals.get(), "DEADLINE_EXCEEDED from the server is a result: no retry");
			assertEquals(1, counting.fastDeadlineRejections.get());
		}
	}

	@Test
	void attachmentsTravelBothWays() {
		Filter responseTagger = new ServerFilter() {
			@Override
			public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
				String tenant = invocation.getAttachment("tenant");
				return next.invoke(invocation).thenApply(result -> result.withAttachments(Map.of("served-for", String.valueOf(tenant))));
			}
		};
		List<String> seenByClient = Collections.synchronizedList(new ArrayList<>());
		ClientFilter responseReader = (next, invocation) -> next.invoke(invocation).whenComplete((result, error) -> {
			if (result != null) {
				seenByClient.add(result.attachments().get("served-for"));
			}
		});

		try (RpcServer tagging = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry())
				.serverFilter(responseTagger).build()) {
			tagging.export(SlowService.class, impl, io.github.sisyfyzhang.steadyrpc.core.config.ServiceOptions.defaults().group("tagging"));
			tagging.start();
			tagging.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			try (RpcClient client = client(responseReader)) {
				SlowService service = client.reference(SlowService.class, ReferenceOptions.defaults().group("tagging"));

				RpcContext.setAttachment("tenant", "t-1");
				String seenByServer = service.whoAmI();
				assertEquals("t-1|true|true", seenByServer, "business code reads inbound attachments, deadline keys included");
				assertEquals(List.of("t-1"), seenByClient, "response attachments reach the client's Result");

				// 消费即清：下一次调用不再带 tenant
				assertEquals("null|true|true", service.whoAmI());
				assertNotNull(seenByClient.get(1));
			}
		}
	}
}
