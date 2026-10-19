package com.feynman.rpc.bench;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationsTest {

	@Test
	void parsesTheThreeUnitsAndDecimals() {
		assertEquals(Duration.ofSeconds(20), Durations.parse("20s"));
		assertEquals(Duration.ofMillis(500), Durations.parse("500ms"));
		assertEquals(Duration.ofMinutes(1), Durations.parse("1m"));
		assertEquals(Duration.ofMillis(2500), Durations.parse("2.5s"));
		assertEquals(Duration.ofNanos(500_000), Durations.parse("0.5ms"));
		assertEquals(Duration.ofSeconds(90), Durations.parse("1.5m"));
		assertEquals(Duration.ofSeconds(3), Durations.parse(" 3 s "), "surrounding / inner blanks are tolerated");
		assertEquals(Duration.ZERO, Durations.parse("0s"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "20", "20sec", "1h", "-1s", "1e3ms", "", "s", "1.s", "0.0000001ms" })
	void rejectsAnythingElse(String text) {
		assertThrows(IllegalArgumentException.class, () -> Durations.parse(text));
	}

	@Test
	void formatsCanonically() {
		assertEquals("60s", Durations.format(Duration.ofSeconds(60)));
		assertEquals("2.5s", Durations.format(Duration.ofMillis(2500)));
		assertEquals("500ms", Durations.format(Duration.ofMillis(500)));
		assertEquals("0.5ms", Durations.format(Duration.ofNanos(500_000)));
		assertEquals("0ms", Durations.format(Duration.ZERO));
		for (String text : new String[] { "20s", "55s", "500ms", "2.5s", "1ms" }) {
			assertEquals(Durations.parse(text), Durations.parse(Durations.format(Durations.parse(text))), "round trip of " + text);
		}
	}
}
