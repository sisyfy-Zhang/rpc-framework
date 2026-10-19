package com.feynman.rpc.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

	@Test
	void escapesQuotesBackslashesAndControlCharacters() {
		assertEquals("\"a\\\"b\\\\c\"\n", Json.write("a\"b\\c"));
		assertEquals("\"line1\\nline2\\r\\t\\b\\f\"\n", Json.write("line1\nline2\r\t\b\f"));
		assertEquals("\"\\u0001\\u001f\\u007f\\u0085\"\n", Json.write("\u0001\u001f\u007f\u0085"));
		assertEquals("\"\\u2028\\u2029\"\n", Json.write("\u2028\u2029"));
		assertEquals("\"中文 é 😀\"\n", Json.write("中文 é 😀"), "non-ASCII (incl. surrogate pairs) is written as UTF-8");
		assertEquals("\"\\ud800x\"\n", Json.write("\ud800x"), "a lone surrogate is escaped");
		assertEquals("\"a/b\"\n", Json.write("a/b"), "slash is not escaped");
	}

	@Test
	void numbersBooleansNullAndEmptyContainers() {
		assertEquals("42\n", Json.write(42));
		assertEquals("-7\n", Json.write(-7L));
		assertEquals("1.5\n", Json.write(1.5));
		assertEquals("100.0\n", Json.write(100.0));
		assertEquals("0.00001\n", Json.write(1e-5), "no exponent notation");
		assertEquals("null\n", Json.write(Double.NaN));
		assertEquals("null\n", Json.write(Double.POSITIVE_INFINITY));
		assertEquals("true\n", Json.write(true));
		assertEquals("null\n", Json.write(null));
		assertEquals("{}\n", Json.write(Map.of()));
		assertEquals("[]\n", Json.write(List.of()));
		assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
	}

	@Test
	void nestedStructureIsPrettyPrintedInInsertionOrder() {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("b", 1);
		root.put("a", List.of(1, 2));
		Map<String, Object> inner = new LinkedHashMap<>();
		inner.put("k", "v");
		root.put("objects", List.of(inner));
		assertEquals("""
				{
				  "b": 1,
				  "a": [1, 2],
				  "objects": [
				    {
				      "k": "v"
				    }
				  ]
				}
				""", Json.write(root));
	}

	@Test
	void whatIsWrittenParsesBackToTheSameValues() {
		Map<String, Object> root = new LinkedHashMap<>();
		String nasty = "q\"uote \\ back\nnew\ttab \u0001 \u2028 中文 😀 yes no on off ~ null # not a comment: [x] {y}";
		root.put("text", nasty);
		root.put("yaml_keywords", List.of("yes", "no", "on", "off", "null", "true", "1e5", "0x10", "010"));
		root.put("int", 7);
		root.put("long", 12_345_678_901L);
		root.put("double", 0.125);
		root.put("whole_double", 3.0);
		root.put("nan", Double.NaN);
		root.put("flag", false);
		root.put("nothing", null);
		root.put("series", new ArrayList<>(Arrays.asList(1L, 0L, 3L)));
		Map<?, ?> back = (Map<?, ?>) Json.parse(Json.write(root));
		assertEquals(nasty, back.get("text"));
		assertEquals(List.of("yes", "no", "on", "off", "null", "true", "1e5", "0x10", "010"), back.get("yaml_keywords"),
				"quoted strings stay strings even when YAML 1.1 would read them as something else unquoted");
		assertEquals(7, ((Number) back.get("int")).intValue());
		assertEquals(12_345_678_901L, ((Number) back.get("long")).longValue());
		assertEquals(0.125, back.get("double"));
		assertEquals(3.0, back.get("whole_double"));
		assertNull(back.get("nan"));
		assertEquals(false, back.get("flag"));
		assertTrue(back.containsKey("nothing") && back.get("nothing") == null);
		assertEquals(3, ((List<?>) back.get("series")).size());
	}
}
