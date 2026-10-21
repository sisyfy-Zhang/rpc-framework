package com.feynman.rpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * rpc 卡实际生效的负载均衡 / 容错策略 / 重试次数，以及各自的来源：卡片（{@code target.load_balance / cluster / retries}）优先，
 * 卡片没写时取命令行（{@code run --loadbalance / --cluster / --retries}），都没有时是框架缺省（重试 0 = 原口径）。
 * 卡片写了、命令行又给了不同的值是用法错误：同一个卡片 id 必须始终代表同一个口径。
 *
 * @param loadBalance SPI 名；null = 框架缺省
 * @param cluster     SPI 名；null = 框架缺省
 * @param retries     {@code ReferenceOptions.retries}
 */
record ClusterChoice(String loadBalance, String cluster, int retries, Map<String, String> sources) {

	static final String SOURCE_CARD = "card";
	static final String SOURCE_COMMAND_LINE = "command line";
	static final String SOURCE_DEFAULT = "default";

	/** 原口径：框架缺省的负载均衡与容错策略，重试 0。 */
	static final ClusterChoice DEFAULTS = new ClusterChoice(null, null, 0,
			Map.of("load_balance", SOURCE_DEFAULT, "cluster", SOURCE_DEFAULT, "retries", SOURCE_DEFAULT));

	/** {@code rr} 是 {@code roundrobin} 的命令行简写。 */
	static String loadBalanceName(String text) {
		if (text == null) {
			return null;
		}
		String t = text.trim();
		return t.equals("rr") ? "roundrobin" : t;
	}

	/**
	 * @throws IllegalArgumentException 卡片与命令行给了不同的值
	 */
	static ClusterChoice resolve(Profile profile, HarnessOptions options) {
		Profile.RpcOptions card = profile.target().rpcOptionsOrDefaults();
		Map<String, String> sources = new LinkedHashMap<>();
		String lb = pick("load_balance", card.loadBalance(), loadBalanceName(options.loadBalance()), sources);
		String cluster = pick("cluster", card.cluster(), options.cluster(), sources);
		Integer retries = pick("retries", card.retries(), options.retries(), sources);
		return new ClusterChoice(lb, cluster, retries == null ? 0 : retries, Map.copyOf(sources));
	}

	private static <T> T pick(String key, T fromCard, T fromCommandLine, Map<String, String> sources) {
		if (fromCard != null) {
			if (fromCommandLine != null && !fromCommandLine.equals(fromCard)) {
				throw new IllegalArgumentException("--" + (key.equals("load_balance") ? "loadbalance" : key) + " " + fromCommandLine
						+ " conflicts with the card's target." + key + ": " + fromCard + " (the card id names one configuration)");
			}
			sources.put(key, SOURCE_CARD);
			return fromCard;
		}
		if (fromCommandLine != null) {
			sources.put(key, SOURCE_COMMAND_LINE);
			return fromCommandLine;
		}
		sources.put(key, SOURCE_DEFAULT);
		return null;
	}
}
