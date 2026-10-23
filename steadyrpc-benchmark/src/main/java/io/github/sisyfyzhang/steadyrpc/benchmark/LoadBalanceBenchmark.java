package io.github.sisyfyzhang.steadyrpc.benchmark;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.LoadBalance;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.LoadStats;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.P2cLoadBalance;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.RoundRobinLoadBalance;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * 不是 {@link SerializationBenchmark} 那种紧循环微基准——这里模拟一个 3 节点小集群，其中一个
 * 节点刻意慢很多（模拟真实负载、GC 暂停、"吵闹邻居"等场景），测量一批并发请求的平均完成延迟，
 * 对比基线 {@link RoundRobinLoadBalance}（对负载视而不见）和 {@link P2cLoadBalance}（采样两个
 * 候选，通过 {@link LoadStats} 选负载更低的那个）。用 {@code SingleShotTime} 是因为每次调用
 * 本身就是完整场景，不是要每秒跑几百万次的那种基准。
 *
 * <p>运行方式：{@code mvn -pl steadyrpc-benchmark package && java -jar steadyrpc-benchmark/target/benchmarks.jar LoadBalanceBenchmark}。
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
public class LoadBalanceBenchmark {

	private static final ServiceKey KEY = new ServiceKey("io.github.sisyfyzhang.steadyrpc.benchmark.Simulated", "", "default");
	// 三个同权节点，按端口区分："fast-1"=1、"fast-2"=2、"slow-1"=3
	private static final List<ServiceInstance> NODES = List.of(
			new ServiceInstance(KEY, "fast-1", 1, Map.of()),
			new ServiceInstance(KEY, "fast-2", 2, Map.of()),
			new ServiceInstance(KEY, "slow-1", 3, Map.of()));
	// 一个节点比另外两个慢一个数量级——模拟降级/过载的节点，不是已经宕掉的节点
	private static final Map<String, Long> SERVICE_TIME_MS = Map.of("fast-1:1", 2L, "fast-2:2", 2L, "slow-1:3", 20L);
	private static final int TOTAL_REQUESTS = 1000;
	private static final int CONCURRENCY = 50;

	@Benchmark
	public long roundRobin() throws InterruptedException {
		return runScenario(new RoundRobinLoadBalance());
	}

	@Benchmark
	public long p2c() throws InterruptedException {
		return runScenario(new P2cLoadBalance());
	}

	/**
	 * 每个场景一份新的 {@link LoadStats}：负载统计现在归调用方实例所有（对应真实运行时的一个 RpcClient），
	 * 两个场景、两次迭代之间不再共享残留的在途计数。
	 *
	 * @return 整批请求的平均每请求延迟（毫秒）
	 */
	private long runScenario(LoadBalance loadBalance) throws InterruptedException {
		LoadStats stats = new LoadStats();
		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
		CountDownLatch latch = new CountDownLatch(TOTAL_REQUESTS);
		AtomicLong totalLatencyNanos = new AtomicLong();

		for (int i = 0; i < TOTAL_REQUESTS; i++) {
			pool.submit(() -> {
				long start = System.nanoTime();
				String address = loadBalance.select(NODES, stats).address();
				stats.begin(address);
				try {
					Thread.sleep(SERVICE_TIME_MS.get(address));
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				} finally {
					stats.end(address);
				}
				totalLatencyNanos.addAndGet(System.nanoTime() - start);
				latch.countDown();
			});
		}
		latch.await();
		pool.shutdown();
		return totalLatencyNanos.get() / 1_000_000 / TOTAL_REQUESTS;
	}
}
