package com.feynman.rpc.bench;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 极简 JSON：写出器手写（对象 / 数组 / 字符串 / 数字 / 布尔 / null，两空格缩进，键序 = Map 迭代序）；
 * 读回复用口径卡已经在用的 SnakeYAML（JSON 是 YAML 1.1 flow 风格的子集），不另写解析器、不引入 JSON 库。
 *
 * <p>为读回安全，写出器刻意只产出两边语义一致的子集：字符串一律双引号，控制字符写成 {@code \\uXXXX} 或
 * {@code \\n} 一类 YAML 同样认识的转义，不写 {@code \\/}；非有限的 double（NaN / ±Infinity）写成 null；
 * double 用十进制定点（{@link BigDecimal#toPlainString()}），且总带小数点——YAML 1.1 把 {@code 1e5} 当字符串。
 */
final class Json {

	private Json() {
	}

	static String write(Object value) {
		StringBuilder sb = new StringBuilder();
		write(sb, value, 0);
		sb.append('\n');
		return sb.toString();
	}

	private static void write(StringBuilder sb, Object value, int indent) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof String s) {
			quote(sb, s);
		} else if (value instanceof Boolean b) {
			sb.append(b);
		} else if (value instanceof Double || value instanceof Float) {
			number(sb, ((Number) value).doubleValue());
		} else if (value instanceof Number n) {
			sb.append(n.longValue());
		} else if (value instanceof Map<?, ?> map) {
			if (map.isEmpty()) {
				sb.append("{}");
				return;
			}
			sb.append("{\n");
			Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<?, ?> e = it.next();
				pad(sb, indent + 1);
				quote(sb, String.valueOf(e.getKey()));
				sb.append(": ");
				write(sb, e.getValue(), indent + 1);
				sb.append(it.hasNext() ? ",\n" : "\n");
			}
			pad(sb, indent);
			sb.append('}');
		} else if (value instanceof List<?> list) {
			if (list.isEmpty()) {
				sb.append("[]");
				return;
			}
			boolean scalars = list.stream().noneMatch(v -> v instanceof Map<?, ?> || v instanceof List<?>);
			if (scalars) {
				// 时间序列这类纯数字数组写在一行，文件仍然可读
				sb.append('[');
				for (int i = 0; i < list.size(); i++) {
					if (i > 0) {
						sb.append(", ");
					}
					write(sb, list.get(i), indent);
				}
				sb.append(']');
				return;
			}
			sb.append("[\n");
			for (int i = 0; i < list.size(); i++) {
				pad(sb, indent + 1);
				write(sb, list.get(i), indent + 1);
				sb.append(i < list.size() - 1 ? ",\n" : "\n");
			}
			pad(sb, indent);
			sb.append(']');
		} else {
			throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass().getName());
		}
	}

	private static void number(StringBuilder sb, double d) {
		if (!Double.isFinite(d)) {
			sb.append("null");
			return;
		}
		String plain = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
		sb.append(plain);
		if (plain.indexOf('.') < 0) {
			sb.append(".0");
		}
	}

	private static void pad(StringBuilder sb, int indent) {
		sb.append("  ".repeat(indent));
	}

	static void quote(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					// 控制字符、C1 区、行 / 段分隔符、非字符 U+FFFE/U+FFFF：JSON 需要或 YAML 读取器不接受原样出现
					if (c < 0x20 || c >= 0x7f && c <= 0x9f || c == ' ' || c == ' ' || c == '￾' || c == '￿'
							|| Character.isSurrogate(c) && !validSurrogateAt(s, i)) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}

	/** 成对的代理项原样写出（UTF-8 编码后是一个合法字符）；落单的写成转义，避免产出非法 UTF-8。 */
	private static boolean validSurrogateAt(String s, int i) {
		char c = s.charAt(i);
		if (Character.isHighSurrogate(c)) {
			return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
		}
		return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
	}

	/** 读回本类写出的 JSON：对象 → Map，数组 → List，整数 → Integer / Long，小数 → Double。 */
	static Object parse(String json) {
		LoaderOptions options = new LoaderOptions();
		options.setAllowDuplicateKeys(false);
		options.setCodePointLimit(64 * 1024 * 1024);
		return new Yaml(new SafeConstructor(options)).load(json);
	}
}
