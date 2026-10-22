package com.feynman.rpc.core.config;

import java.time.Duration;

/**
 * 客户端离群摘除（outlier detection）的配置，见 {@code com.feynman.rpc.core.cluster.OutlierDetector}。缺省开启，阈值保守：
 *
 * <ul>
 *   <li><b>数据源</b>：按提供者地址的滑动窗口（{@value #WINDOW_SECONDS} 个 1 秒桶），统计尝试数与"坏结果"——本地超时、服务端回
 *       DEADLINE_EXCEEDED、传输失败（含出站硬上限"未发出"）、SERVER_OVERLOADED。业务异常与其它服务端错误状态不算坏结果
 *       （节点答复了，说明它活着）；</li>
 *   <li><b>摘除条件</b>（任一）：窗口内尝试 ≥ {@code minRequests} 且坏结果占比 ≥ {@code failureRatio}；或连续坏结果 ≥ {@code consecutiveFailures}
 *       （0 = 不用这个条件）；</li>
 *   <li><b>摘除时长</b>：{@code baseEjection} × 该地址累计被摘次数，上限 {@code maxEjection}；到期自动回到候选集（时间驱动，
 *       不做半开探测、不做主动健康检查、不按延迟摘除），回来时连续坏结果计数清零，窗口保留；放回后连续健康（没有再被摘）
 *       每满一个 {@code maxEjection}，累计被摘次数减 1（最低 1）；</li>
 *   <li><b>最大摘除比例</b> {@code maxEjectionPercent}：摘掉之后健康节点会少于 {@code max(1, ceil(n × (100 − p) / 100))} 时不摘
 *       （n = 该服务当前的提供者数）；万一全部候选都处于摘除期（推送收窄了列表等），退回全部候选——宁可打到坏节点，也不无节点可用。</li>
 * </ul>
 *
 * @param enabled             是否开启（缺省 true）
 * @param minRequests         比例条件生效所需的窗口内最少尝试数（缺省 {@value #DEFAULT_MIN_REQUESTS}，≥ 1）
 * @param failureRatio        坏结果占比阈值（缺省 {@value #DEFAULT_FAILURE_RATIO}，(0, 1]）
 * @param consecutiveFailures 连续坏结果阈值（缺省 {@value #DEFAULT_CONSECUTIVE_FAILURES}，0 = 关闭这个条件）
 * @param baseEjection        第一次摘除的时长（缺省 10s，> 0）；第 k 次摘除时长为 k 倍
 * @param maxEjection         摘除时长上限（缺省 60s，≥ baseEjection）
 * @param maxEjectionPercent  最多摘除的提供者比例（百分数，缺省 {@value #DEFAULT_MAX_EJECTION_PERCENT}，0 ~ 100）
 */
public record OutlierDetectionConfig(boolean enabled, int minRequests, double failureRatio, int consecutiveFailures,
		Duration baseEjection, Duration maxEjection, int maxEjectionPercent) {

	/** 滑动窗口的桶数（每桶 1 秒）。固定值：窗口长度不是可调口径。 */
	public static final int WINDOW_SECONDS = 10;
	public static final boolean DEFAULT_ENABLED = true;
	public static final int DEFAULT_MIN_REQUESTS = 20;
	public static final double DEFAULT_FAILURE_RATIO = 0.5;
	public static final int DEFAULT_CONSECUTIVE_FAILURES = 5;
	public static final Duration DEFAULT_BASE_EJECTION = Duration.ofSeconds(10);
	public static final Duration DEFAULT_MAX_EJECTION = Duration.ofSeconds(60);
	public static final int DEFAULT_MAX_EJECTION_PERCENT = 50;

	private static final OutlierDetectionConfig DEFAULTS = new OutlierDetectionConfig(DEFAULT_ENABLED, DEFAULT_MIN_REQUESTS,
			DEFAULT_FAILURE_RATIO, DEFAULT_CONSECUTIVE_FAILURES, DEFAULT_BASE_EJECTION, DEFAULT_MAX_EJECTION, DEFAULT_MAX_EJECTION_PERCENT);

	public OutlierDetectionConfig {
		if (minRequests < 1) {
			throw new IllegalArgumentException("outlierDetection.minRequests must be >= 1: " + minRequests);
		}
		if (!(failureRatio > 0 && failureRatio <= 1)) {
			throw new IllegalArgumentException("outlierDetection.failureRatio must be in (0, 1]: " + failureRatio);
		}
		if (consecutiveFailures < 0) {
			throw new IllegalArgumentException("outlierDetection.consecutiveFailures must be >= 0 (0 = off): " + consecutiveFailures);
		}
		if (baseEjection == null) {
			baseEjection = DEFAULT_BASE_EJECTION;
		}
		if (maxEjection == null) {
			maxEjection = baseEjection.compareTo(DEFAULT_MAX_EJECTION) > 0 ? baseEjection : DEFAULT_MAX_EJECTION;
		}
		if (baseEjection.isNegative() || baseEjection.isZero()) {
			throw new IllegalArgumentException("outlierDetection.baseEjection must be positive: " + baseEjection);
		}
		if (maxEjection.compareTo(baseEjection) < 0) {
			throw new IllegalArgumentException("outlierDetection.maxEjection (" + maxEjection + ") must be >= baseEjection (" + baseEjection + ")");
		}
		if (maxEjectionPercent < 0 || maxEjectionPercent > 100) {
			throw new IllegalArgumentException("outlierDetection.maxEjectionPercent must be 0..100: " + maxEjectionPercent);
		}
	}

	/** 缺省配置（开启，阈值见类注释）。 */
	public static OutlierDetectionConfig defaults() {
		return DEFAULTS;
	}

	/** 关闭离群摘除（其余字段取缺省值，不起作用）。 */
	public static OutlierDetectionConfig disabled() {
		return DEFAULTS.toBuilder().enabled(false).build();
	}

	public static Builder builder() {
		return new Builder(DEFAULTS);
	}

	/** 以本配置为起点的 builder（只改其中几项时用）。 */
	public Builder toBuilder() {
		return new Builder(this);
	}

	/** 逐项设置；未设置的项保留起点（{@link #builder()} 的起点是缺省配置）。校验在 {@link #build()} 时进行。 */
	public static final class Builder {
		private boolean enabled;
		private int minRequests;
		private double failureRatio;
		private int consecutiveFailures;
		private Duration baseEjection;
		private Duration maxEjection;
		private int maxEjectionPercent;

		private Builder(OutlierDetectionConfig from) {
			this.enabled = from.enabled;
			this.minRequests = from.minRequests;
			this.failureRatio = from.failureRatio;
			this.consecutiveFailures = from.consecutiveFailures;
			this.baseEjection = from.baseEjection;
			this.maxEjection = from.maxEjection;
			this.maxEjectionPercent = from.maxEjectionPercent;
		}

		public Builder enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		public Builder minRequests(int minRequests) {
			this.minRequests = minRequests;
			return this;
		}

		public Builder failureRatio(double failureRatio) {
			this.failureRatio = failureRatio;
			return this;
		}

		public Builder consecutiveFailures(int consecutiveFailures) {
			this.consecutiveFailures = consecutiveFailures;
			return this;
		}

		public Builder baseEjection(Duration baseEjection) {
			this.baseEjection = baseEjection;
			return this;
		}

		public Builder maxEjection(Duration maxEjection) {
			this.maxEjection = maxEjection;
			return this;
		}

		public Builder maxEjectionPercent(int maxEjectionPercent) {
			this.maxEjectionPercent = maxEjectionPercent;
			return this;
		}

		public OutlierDetectionConfig build() {
			return new OutlierDetectionConfig(enabled, minRequests, failureRatio, consecutiveFailures, baseEjection, maxEjection,
					maxEjectionPercent);
		}
	}
}
