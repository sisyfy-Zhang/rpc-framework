package com.feynman.rpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 仓库里的对比卡与并发扫描卡：rpc 卡能加载；sweep 卡与 compare-closed64-rpc-v1 只差 {@code load.concurrency}；
 * 各对比对象的卡（gRPC / Dubbo，由各自模块的测试按提供者完整加载）与同负载的 rpc 卡只差 transport / serializer。
 */
class ComparisonCardsTest {

	private static final Path PROFILES = Path.of("..", "bench", "profiles");

	@Test
	void rpcComparisonAndSweepCardsLoad() throws Exception {
		Profile closed = ProfileLoader.load(PROFILES.resolve("compare-closed64-rpc-v1.yaml"));
		assertEquals(64, closed.load().concurrency());
		assertEquals("none (--direct)", closed.isolation().registry());
		Profile open = ProfileLoader.load(PROFILES.resolve("compare-open10k-rpc-v1.yaml"));
		assertEquals(10000, open.load().rate());
		assertEquals(closed.load().duration(), open.load().duration());
		assertEquals(closed.load().windowEnd(), open.load().windowEnd());
	}

	@Test
	void sweepCardsDifferFromTheBaselineOnlyInConcurrency() throws Exception {
		Map<String, Object> baseline = comparable(ProfileLoader.load(PROFILES.resolve("compare-closed64-rpc-v1.yaml")));
		for (int n : new int[] { 128, 256 }) {
			Profile sweep = ProfileLoader.load(PROFILES.resolve("sweep-closed" + n + "-rpc-v1.yaml"));
			assertEquals(n, sweep.load().concurrency());
			assertEquals(baseline, comparable(sweep), "sweep-closed" + n + "-rpc-v1 must match compare-closed64-rpc-v1 except load.concurrency");
		}
	}

	@Test
	void comparisonTargetCardsDifferFromTheRpcCardOnlyInTransportAndSerializer() throws Exception {
		for (String load : List.of("closed64", "open10k")) {
			List<String> rpc = keyLines(PROFILES.resolve("compare-" + load + "-rpc-v1.yaml"));
			for (String transport : List.of("grpc", "dubbo")) {
				Path card = PROFILES.resolve("compare-" + load + "-" + transport + "-v1.yaml");
				if (!Files.exists(card)) {
					continue;
				}
				List<String> other = keyLines(card);
				assertEquals(rpc.size(), other.size(), card.toString());
				for (int i = 0; i < rpc.size(); i++) {
					String a = rpc.get(i);
					String b = other.get(i);
					String key = a.substring(0, a.indexOf(':') + 1);
					if (List.of("profile:", "question:", "transport:", "serializer:").contains(key.trim())) {
						assertTrue(b.startsWith(key), card + ": line " + i + " is " + b);
					} else {
						assertEquals(a, b, card + ": only profile / question / transport / serializer may differ");
					}
				}
				assertTrue(other.contains("  transport: " + transport), card.toString());
			}
		}
	}

	/** 连接数对齐卡（-c2）与对应的开箱即用卡、连接数扫描卡（sweep-cN）与 sweep-closed128 只差 target.connections。 */
	@Test
	void connectionVariantsDifferOnlyInTargetConnections() throws Exception {
		List<String[]> pairs = new ArrayList<>();
		for (String load : List.of("closed64", "open10k")) {
			for (String transport : List.of("grpc", "dubbo")) {
				pairs.add(new String[] { "compare-" + load + "-" + transport + "-v1", "compare-" + load + "-" + transport + "-c2-v1", "2" });
			}
		}
		pairs.add(new String[] { "sweep-closed128-rpc-v1", "sweep-c4-closed128-rpc-v1", "4" });
		pairs.add(new String[] { "compare-closed64-rpc-v1", "sweep-c1-closed64-rpc-v1", "1" });
		pairs.add(new String[] { "compare-closed64-rpc-direct-v1", "sweep-c1-closed64-rpc-direct-v1", "1" });
		pairs.add(new String[] { "sweep-closed128-rpc-v1", "sweep-c8-closed128-rpc-v1", "8" });
		for (String[] pair : pairs) {
			List<String> base = keyLines(PROFILES.resolve(pair[0] + ".yaml"));
			List<String> variant = new ArrayList<>(keyLines(PROFILES.resolve(pair[1] + ".yaml")));
			assertTrue(variant.remove("  connections: " + pair[2]), pair[1] + " declares target.connections: " + pair[2]);
			assertEquals(base.size(), variant.size(), pair[1]);
			for (int i = 0; i < base.size(); i++) {
				String a = base.get(i);
				if (a.startsWith("profile:") || a.startsWith("question:")) {
					continue;
				}
				assertEquals(a, variant.get(i), pair[1] + " must match " + pair[0] + " except target.connections");
			}
		}
		Profile c8 = ProfileLoader.load(PROFILES.resolve("sweep-c8-closed128-rpc-v1.yaml"));
		assertEquals(8, c8.target().connections());
		assertEquals(128, c8.load().concurrency());
		assertEquals(null, ProfileLoader.load(PROFILES.resolve("compare-closed64-rpc-v1.yaml")).target().connections(),
				"the out-of-the-box cards leave connections to each framework's default");
	}

	/** 执行器对照卡（-direct）与对应的缺省卡只差 {@code target.server_executor: direct}（以及 id、问题）。 */
	@Test
	void directExecutorVariantsDifferOnlyInServerExecutor() throws Exception {
		List<String[]> pairs = List.of(new String[] { "compare-closed64-rpc-v1", "compare-closed64-rpc-direct-v1" },
				new String[] { "compare-open10k-rpc-v1", "compare-open10k-rpc-direct-v1" },
				new String[] { "sweep-c1-closed64-rpc-v1", "sweep-c1-closed64-rpc-direct-v1" });
		for (String[] pair : pairs) {
			List<String> base = keyLines(PROFILES.resolve(pair[0] + ".yaml"));
			List<String> variant = new ArrayList<>(keyLines(PROFILES.resolve(pair[1] + ".yaml")));
			assertTrue(variant.remove("  server_executor: direct"), pair[1] + " declares target.server_executor: direct");
			assertEquals(base.size(), variant.size(), pair[1]);
			for (int i = 0; i < base.size(); i++) {
				String a = base.get(i);
				if (a.startsWith("profile:") || a.startsWith("question:")) {
					continue;
				}
				assertEquals(a, variant.get(i), pair[1] + " must match " + pair[0] + " except target.server_executor");
			}
			assertEquals("direct", ProfileLoader.load(PROFILES.resolve(pair[1] + ".yaml")).target().serverExecutor());
			assertEquals("fixed", ProfileLoader.load(PROFILES.resolve(pair[0] + ".yaml")).target().serverExecutor());
		}
		assertEquals(1, ProfileLoader.load(PROFILES.resolve("sweep-c1-closed64-rpc-v1.yaml")).target().connections());
	}

	/** 卡片的非注释、非空行。 */
	private static List<String> keyLines(Path card) throws Exception {
		List<String> lines = new ArrayList<>();
		for (String line : Files.readAllLines(card, StandardCharsets.UTF_8)) {
			if (!line.isBlank() && !line.stripLeading().startsWith("#")) {
				lines.add(line);
			}
		}
		return lines;
	}

	/** 去掉 id、问题与并发之后的解析结构。 */
	private static Map<String, Object> comparable(Profile profile) {
		Map<String, Object> json = new LinkedHashMap<>(profile.toJson());
		json.remove("profile");
		json.remove("question");
		@SuppressWarnings("unchecked")
		Map<String, Object> load = new LinkedHashMap<>((Map<String, Object>) json.get("load"));
		load.remove("concurrency");
		json.put("load", load);
		return json;
	}
}
