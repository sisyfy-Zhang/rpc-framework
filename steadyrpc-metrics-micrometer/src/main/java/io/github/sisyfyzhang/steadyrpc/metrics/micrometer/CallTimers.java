package io.github.sisyfyzhang.steadyrpc.metrics.micrometer;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 一个 Filter 实例的 Timer 缓存：{@code service → method → status → Timer}。每次调用只做三次 ConcurrentHashMap 读取，
 * 不在热路径上构造 Meter.Id、也不去 registry 里查表；首次出现的组合才注册（registry 对同名同 tag 去重，竞态下拿到同一个 Timer）。
 *
 * <p>直方图：{@code publishPercentileHistogram()} 让 Prometheus 端可以 {@code histogram_quantile}，并且能跨实例聚合（客户端算好的
 * 分位数不能相加）；桶的上界截到 {@link #MAX_EXPECTED}（10s），下界按端取：客户端 {@link #CLIENT_MIN_EXPECTED}（100µs，一次往返
 * 至少含一次网络与两次编解码），服务端 {@link #SERVER_MIN_EXPECTED}（1µs：轻量方法的服务端耗时只有几微秒，下界取 100µs 时
 * 几乎全部样本落进第一个桶，p99 无法分辨）。
 */
final class CallTimers {

	static final Duration CLIENT_MIN_EXPECTED = Duration.ofNanos(100_000);
	static final Duration SERVER_MIN_EXPECTED = Duration.ofNanos(1_000);
	static final Duration MAX_EXPECTED = Duration.ofSeconds(10);

	static final String TAG_SERVICE = "service";
	static final String TAG_METHOD = "method";
	static final String TAG_STATUS = "status";

	private final MeterRegistry registry;
	private final String name;
	private final String description;
	private final Duration minExpected;
	private final ConcurrentHashMap<String, ConcurrentHashMap<Method, ConcurrentHashMap<String, Timer>>> timers = new ConcurrentHashMap<>();

	CallTimers(MeterRegistry registry, String name, String description, Duration minExpected) {
		if (registry == null) {
			throw new IllegalArgumentException("registry must not be null");
		}
		this.registry = registry;
		this.name = name;
		this.description = description;
		this.minExpected = minExpected;
	}

	/** 单调时钟读数（纳秒），取 registry 的时钟：测试可以换成 MockClock。 */
	long now() {
		return registry.config().clock().monotonicTime();
	}

	void record(String service, Method method, String status, long startNanos) {
		long elapsed = now() - startNanos;
		timer(service, method, status).record(Math.max(0, elapsed), TimeUnit.NANOSECONDS);
	}

	Timer timer(String service, Method method, String status) {
		ConcurrentHashMap<String, Timer> byStatus = timers
				.computeIfAbsent(service, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(method, k -> new ConcurrentHashMap<>());
		Timer timer = byStatus.get(status);
		if (timer == null) {
			timer = byStatus.computeIfAbsent(status, s -> Timer.builder(name)
					.description(description)
					.tag(TAG_SERVICE, service)
					.tag(TAG_METHOD, method.getName())
					.tag(TAG_STATUS, s)
					.publishPercentileHistogram()
					.minimumExpectedValue(minExpected)
					.maximumExpectedValue(MAX_EXPECTED)
					.register(registry));
		}
		return timer;
	}
}
