package com.feynman.rpc.bench;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析并校验后的口径卡（不可变）。字段与 YAML 一一对应，含义见 docs/benchmarking.md；构造只经
 * {@link ProfileLoader}，因此这里的值都已通过校验。
 *
 * @param rawYaml 卡片原文，原样进 JSON（事实源要能回答"当时到底跑的是什么"）
 */
record Profile(String id, String question, Target target, Load load, Isolation isolation, Measure measure,
		List<Assertion> assertions, String rawYaml) {

	static final String MODEL_CLOSED = "closed";
	static final String MODEL_OPEN = "open";

	/**
	 * @param serializer {@code transport = rpc} 时必填；{@code http} 时为 null（请求体就是原始字节，不经序列化器）
	 * @param providers {@code http} 时固定为 1（一个 URL）
	 * @param httpUrl {@code target.http.url}，只在 {@code transport = http} 时非 null
	 * @param httpConnections {@code target.http.connections}（开环的连接池大小，缺省 {@value #DEFAULT_HTTP_CONNECTIONS}）；
	 *                        闭环每个虚拟用户独占一条连接，此值只记录不使用；{@code rpc} 时为 0
	 */
	record Target(String transport, String service, String method, String payloadKind, int payloadSize, String serializer,
			int providers, String httpUrl, int httpConnections) {

		boolean http() {
			return TRANSPORT_HTTP.equals(transport);
		}
	}

	static final String TRANSPORT_RPC = "rpc";
	static final String TRANSPORT_HTTP = "http";
	static final int DEFAULT_HTTP_CONNECTIONS = 64;

	/**
	 * @param concurrency closed 必填；open 时可出现但不使用（记为 null 以外的值也只是被记录）
	 * @param rate open 必填（req/s）；closed 时同上
	 */
	record Load(String model, Integer concurrency, Integer rate, Duration duration, Duration warmup, Duration windowStart,
			Duration windowEnd, int rounds, Duration timeout) {

		boolean closed() {
			return MODEL_CLOSED.equals(model);
		}

		/** 开环在途上限 = rate × timeout × 2（至少 1）。超过即 backlog_dropped，本轮无效。 */
		long inflightCap() {
			double cap = (double) rate * (timeout.toNanos() / 1e9) * 2;
			return Math.max(1, (long) Math.ceil(cap));
		}

		Duration window() {
			return windowEnd.minus(windowStart);
		}
	}

	record Isolation(String clientProcess, String serverProcess, boolean sameHost, String registry) {
	}

	record Measure(int significantDigits, Duration histogramMax, List<String> report) {
	}

	/**
	 * 一条断言。{@code metric} 为 errors / throughput / p50 / p95 / p99 / p999 / max；{@code upperBound} 为 true 表示
	 * "实测 ≤ 阈值"（*_max），false 表示"实测 ≥ 阈值"（throughput_min）。延迟阈值以毫秒存。
	 */
	record Assertion(String key, String metric, boolean upperBound, double threshold, String thresholdText) {
	}

	/** 解析后的结构，进 JSON 的 {@code profile.parsed}。 */
	Map<String, Object> toJson() {
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("transport", target.transport());
		t.put("service", target.service());
		t.put("method", target.method());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("kind", target.payloadKind());
		payload.put("size", target.payloadKind().equals("bytes") ? target.payloadSize() : null);
		t.put("payload", payload);
		t.put("serializer", target.serializer());
		t.put("providers", target.providers());
		if (target.http()) {
			Map<String, Object> http = new LinkedHashMap<>();
			http.put("url", target.httpUrl());
			http.put("connections", target.httpConnections());
			t.put("http", http);
		}

		Map<String, Object> l = new LinkedHashMap<>();
		l.put("model", load.model());
		l.put("concurrency", load.concurrency());
		l.put("rate", load.rate());
		l.put("duration", Durations.format(load.duration()));
		l.put("duration_ms", Durations.millis(load.duration()));
		l.put("warmup", Durations.format(load.warmup()));
		l.put("warmup_ms", Durations.millis(load.warmup()));
		l.put("steady_window", List.of(Durations.format(load.windowStart()), Durations.format(load.windowEnd())));
		l.put("steady_window_ms", List.of(Durations.millis(load.windowStart()), Durations.millis(load.windowEnd())));
		l.put("rounds", load.rounds());
		l.put("timeout", Durations.format(load.timeout()));
		l.put("timeout_ms", Durations.millis(load.timeout()));
		l.put("inflight_cap", load.closed() ? null : load.inflightCap());

		Map<String, Object> i = new LinkedHashMap<>();
		i.put("client_process", isolation.clientProcess());
		i.put("server_process", isolation.serverProcess());
		i.put("same_host", isolation.sameHost());
		i.put("registry", isolation.registry());

		Map<String, Object> histogram = new LinkedHashMap<>();
		histogram.put("significant_digits", measure.significantDigits());
		histogram.put("max", Durations.format(measure.histogramMax()));
		histogram.put("max_ms", Durations.millis(measure.histogramMax()));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("histogram", histogram);
		m.put("report", measure.report());

		List<Object> a = new ArrayList<>();
		for (Assertion assertion : assertions) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("key", assertion.key());
			entry.put("metric", assertion.metric());
			entry.put("bound", assertion.upperBound() ? "max" : "min");
			entry.put("threshold", assertion.thresholdText());
			a.add(entry);
		}

		Map<String, Object> root = new LinkedHashMap<>();
		root.put("profile", id);
		root.put("question", question);
		root.put("target", t);
		root.put("load", l);
		root.put("isolation", i);
		root.put("measure", m);
		root.put("assert", a);
		return root;
	}
}
