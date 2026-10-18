package com.feynman.rpc.core.registry;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点元数据的查询串编解码：{@code weight=1&proto=1&ts=...&pid=...}。
 *
 * <p>为什么不用 JSON：core 不引入 JSON 库；查询串人眼可读（zkCli get 一眼看懂）、可无限追加键
 * （以后的 tags），对于扁平的字符串 map 足够。键值做 URL 编码，避免值里出现 {@code &}/{@code =} 时错位。
 */
public final class InstanceMetadata {

	public static final String WEIGHT = "weight";
	public static final String PROTOCOL = "proto";
	public static final String TIMESTAMP = "ts";
	public static final String PID = "pid";

	private InstanceMetadata() {
	}

	public static String encode(Map<String, String> metadata) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : metadata.entrySet()) {
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
					.append('=')
					.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

	public static Map<String, String> decode(String text) {
		Map<String, String> result = new LinkedHashMap<>();
		if (text == null || text.isEmpty()) {
			return result;
		}
		for (String pair : text.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			int eq = pair.indexOf('=');
			String key = eq < 0 ? pair : pair.substring(0, eq);
			String value = eq < 0 ? "" : pair.substring(eq + 1);
			result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
		}
		return result;
	}
}
