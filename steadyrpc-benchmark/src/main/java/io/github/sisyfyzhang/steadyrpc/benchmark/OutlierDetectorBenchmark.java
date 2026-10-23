package io.github.sisyfyzhang.steadyrpc.benchmark;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.cluster.OutlierDetector;
import io.github.sisyfyzhang.steadyrpc.core.config.OutlierDetectionConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * 离群摘除在调用热路径上的单次开销（单线程紧循环）：
 * <ul>
 *   <li>{@link #filterNoneEjected}：选点前过滤候选（3 个提供者、没有节点被摘）——预期只是一次 volatile 读、原样返回列表；</li>
 *   <li>{@link #onSuccess}：每次有响应的尝试（桶 CAS + 连续计数读）；</li>
 *   <li>{@link #onFailureNoEjection}：每次坏结果、但不触发摘除（桶 CAS × 2、连续计数自增、汇总 10 个桶判断比例条件）。</li>
 * </ul>
 * 跑法：{@code java -jar steadyrpc-benchmark/target/benchmarks.jar OutlierDetectorBenchmark -prof gc}（看 {@code gc.alloc.rate.norm}，预期 0 B/op）。
 * 结果见 docs/probes/outlier-detection.md「无故障对照」。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class OutlierDetectorBenchmark {

	private static final ServiceKey KEY = new ServiceKey("io.github.sisyfyzhang.steadyrpc.benchmark.Simulated", "", "default");
	private static final List<ServiceInstance> NODES = List.of(
			new ServiceInstance(KEY, "10.0.0.1", 9000, Map.of()),
			new ServiceInstance(KEY, "10.0.0.2", 9000, Map.of()),
			new ServiceInstance(KEY, "10.0.0.3", 9000, Map.of()));
	private static final String ADDRESS = NODES.get(0).address();

	private OutlierDetector defaults;
	/** 坏结果永远不触发摘除：连续条件关闭、比例条件的最少尝试数不可达（但每次仍汇总窗口、走完整的判断）。 */
	private OutlierDetector neverEjects;

	@Setup
	public void setup() {
		defaults = new OutlierDetector(OutlierDetectionConfig.defaults(), "bench", () -> NODES, new OutlierDetector.Stats());
		neverEjects = new OutlierDetector(OutlierDetectionConfig.builder().consecutiveFailures(0).minRequests(Integer.MAX_VALUE).build(),
				"bench", () -> NODES, new OutlierDetector.Stats());
		// 先建好节点状态（首次见到地址时的 computeIfAbsent 不在测量里）
		defaults.onSuccess(ADDRESS);
		neverEjects.onSuccess(ADDRESS);
	}

	@Benchmark
	public List<ServiceInstance> filterNoneEjected() {
		return defaults.filter(NODES);
	}

	@Benchmark
	public void onSuccess() {
		defaults.onSuccess(ADDRESS);
	}

	@Benchmark
	public void onFailureNoEjection() {
		neverEjects.onFailure(ADDRESS, OutlierDetector.TIMEOUT);
	}
}
