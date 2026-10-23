package io.github.sisyfyzhang.steadyrpc.bench;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 口径卡里的时长：{@code <数字><单位>}，单位 {@code ms} / {@code s} / {@code m}，数字可带小数（{@code 2.5s}）。
 * 不接受裸数字：{@code duration: 60} 是 60 秒还是 60 毫秒，卡片上必须写清楚。
 */
final class Durations {

	private static final Pattern FORMAT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ms|s|m)");

	private Durations() {
	}

	/** @throws IllegalArgumentException 格式不对、为负或小于 1 纳秒精度能表达的值 */
	static Duration parse(String text) {
		if (text == null) {
			throw new IllegalArgumentException("duration is missing");
		}
		Matcher m = FORMAT.matcher(text.trim());
		if (!m.matches()) {
			throw new IllegalArgumentException("\"" + text + "\" is not a duration; expected <number><unit> with unit ms, s or m"
					+ " (e.g. 500ms, 20s, 1m)");
		}
		BigDecimal value = new BigDecimal(m.group(1));
		BigDecimal nanosPerUnit = switch (m.group(2)) {
			case "ms" -> BigDecimal.valueOf(1_000_000L);
			case "s" -> BigDecimal.valueOf(1_000_000_000L);
			default -> BigDecimal.valueOf(60_000_000_000L);
		};
		BigDecimal nanos = value.multiply(nanosPerUnit);
		if (nanos.stripTrailingZeros().scale() > 0) {
			throw new IllegalArgumentException("\"" + text + "\" is finer than one nanosecond");
		}
		return Duration.ofNanos(nanos.longValueExact());
	}

	/** 人读的规范形式：1 秒及以上写秒（{@code 60s}、{@code 2.5s}），其余写毫秒（{@code 500ms}、{@code 0.5ms}）。 */
	static String format(Duration d) {
		long nanos = d.toNanos();
		if (nanos >= 1_000_000_000L && nanos % 1_000_000L == 0) {
			return new BigDecimal(nanos).movePointLeft(9).stripTrailingZeros().toPlainString() + "s";
		}
		return new BigDecimal(nanos).movePointLeft(6).stripTrailingZeros().toPlainString() + "ms";
	}

	static double millis(Duration d) {
		return d.toNanos() / 1_000_000.0;
	}
}
