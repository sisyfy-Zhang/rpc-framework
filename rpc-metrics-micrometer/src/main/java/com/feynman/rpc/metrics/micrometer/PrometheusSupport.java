package com.feynman.rpc.metrics.micrometer;

import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * 唯一引用 micrometer-registry-prometheus 类型的地方。{@link PrometheusEndpoint} 先用类名确认依赖存在，才会触碰本类——
 * 依赖缺失时本类从不被加载，调用方看到的是 {@link PrometheusEndpoint} 给出的说明，而不是 NoClassDefFoundError。
 */
final class PrometheusSupport {

	private PrometheusSupport() {
	}

	static MeterRegistry newRegistry() {
		return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
	}

	static Supplier<String> scraper(MeterRegistry registry) {
		PrometheusMeterRegistry prometheus = find(registry);
		if (prometheus == null) {
			throw new IllegalArgumentException("PrometheusEndpoint needs a PrometheusMeterRegistry (or a CompositeMeterRegistry "
					+ "containing one), got " + registry.getClass().getName());
		}
		return prometheus::scrape;
	}

	private static PrometheusMeterRegistry find(MeterRegistry registry) {
		if (registry instanceof PrometheusMeterRegistry prometheus) {
			return prometheus;
		}
		if (registry instanceof CompositeMeterRegistry composite) {
			for (MeterRegistry child : composite.getRegistries()) {
				PrometheusMeterRegistry found = find(child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}
}
