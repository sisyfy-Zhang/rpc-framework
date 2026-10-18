package com.feynman.rpc.core.server;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.config.RpcServerConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 远端堆栈上限：字节数（含截断标记）不超过上限，截断落在字符边界上，不切开多字节字符与代理对。 */
class RemoteStackTraceLimitTest {

	@Test
	void shortStackIsUntouched() {
		String stack = "java.lang.IllegalStateException: boom\n\tat a.B.c(B.java:1)\n";
		assertSame(stack, ServiceDispatcher.limit(stack, 8192));
	}

	@Test
	void longStackIsCutAtACharacterBoundaryAndMarked() {
		// 中文 3 字节、emoji 4 字节（代理对），让截断点大概率落在多字节字符中间
		String stack = "库存不足😀".repeat(1000);
		for (int max : new int[] { 256, 257, 258, 259, 1000, 8192 }) {
			String limited = ServiceDispatcher.limit(stack, max);
			int bytes = limited.getBytes(StandardCharsets.UTF_8).length;
			assertTrue(bytes <= max, "max " + max + " but got " + bytes + " bytes");
			assertTrue(bytes > max - 8, "should use almost the whole budget: " + bytes + " of " + max);
			assertTrue(limited.endsWith(RpcServerConfig.TRUNCATION_MARKER), limited);
			String body = limited.substring(0, limited.length() - RpcServerConfig.TRUNCATION_MARKER.length());
			assertTrue(stack.startsWith(body), "a prefix of the original");
			assertTrue(body.isEmpty() || !Character.isHighSurrogate(body.charAt(body.length() - 1)), "no split surrogate pair");
		}
	}

	@Test
	void configRejectsLimitsTooSmallToBeUseful() {
		assertThrows(IllegalArgumentException.class, () -> RpcServerConfig.builder().remoteStackTraceMaxBytes(10).build());
		assertEquals(8192, RpcServerConfig.builder().build().remoteStackTraceMaxBytes());
	}
}
