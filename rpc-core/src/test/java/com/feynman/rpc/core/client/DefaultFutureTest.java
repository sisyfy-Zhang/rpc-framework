package com.feynman.rpc.core.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultFutureTest {

	@Test
	void completesWhenMatchingReplyArrives() throws Exception {
		long requestId = DefaultFuture.nextRequestId();
		DefaultFuture df = new DefaultFuture(requestId, 5, TimeUnit.SECONDS);

		RpcMessage reply = new RpcMessage(requestId, MessageType.RESPONSE, (byte) 1, Status.OK, new byte[0]);
		DefaultFuture.receive(reply);

		RpcMessage result = df.future().get(1, TimeUnit.SECONDS);
		assertEquals(requestId, result.getRequestId());
	}

	@Test
	void doesNotBlockCallerThreadBeforeCompletion() {
		long requestId = DefaultFuture.nextRequestId();
		DefaultFuture df = new DefaultFuture(requestId, 5, TimeUnit.SECONDS);

		// 构造 future 并读取它的句柄不应阻塞，无论此时是否已经有响应到达
		CompletableFuture<RpcMessage> future = df.future();
		assertTrue(!future.isDone());
	}

	@Test
	void timesOutWithoutAReply() {
		long requestId = DefaultFuture.nextRequestId();
		DefaultFuture df = new DefaultFuture(requestId, 200, TimeUnit.MILLISECONDS);

		CompletionException ex = assertThrows(CompletionException.class, () -> df.future().join());
		assertTrue(DefaultFuture.isTimeout(ex.getCause()) || DefaultFuture.isTimeout(ex));
	}

	@Test
	void unmatchedReplyIsIgnoredSafely() {
		// 对没有任何调用方在等待的 requestId 调用 receive()，应该是空操作，而不是抛异常
		DefaultFuture.receive(new RpcMessage(999_999_999L, MessageType.RESPONSE, (byte) 1, Status.OK, new byte[0]));
	}
}
