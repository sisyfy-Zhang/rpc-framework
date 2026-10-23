package io.github.sisyfyzhang.steadyrpc.bench;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 压测负载：固定种子，同一张卡每次运行发送的内容完全相同。 */
final class Payloads {

	static final long SEED = 20_261_019L;

	private Payloads() {
	}

	static byte[] bytes(int size) {
		byte[] payload = new byte[size];
		new Random(SEED).nextBytes(payload);
		return payload;
	}

	static Order order() {
		Random random = new Random(SEED);
		List<String> skus = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			skus.add("SKU-" + (100_000 + random.nextInt(900_000)));
		}
		return new Order(random.nextLong() & Long.MAX_VALUE, "NO-" + Long.toHexString(random.nextLong()),
				"C-" + random.nextInt(1_000_000), 1 + random.nextInt(20), 100L + random.nextInt(1_000_000), skus,
				Instant.ofEpochSecond(1_760_000_000L + random.nextInt(10_000_000), random.nextInt(1_000_000_000)),
				LocalDate.of(2026, 1, 1).plusDays(random.nextInt(365)));
	}
}
