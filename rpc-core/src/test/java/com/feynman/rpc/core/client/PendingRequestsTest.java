package com.feynman.rpc.core.client;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;

import io.netty.buffer.Unpooled;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingRequestsTest {

	@Test
	void completesWhenMatchingReplyArrives() throws Exception {
		PendingRequests pending = new PendingRequests();
		long requestId = pending.nextRequestId();
		CompletableFuture<RpcMessage> future = pending.register(requestId, 5, TimeUnit.SECONDS);

		pending.complete(RpcMessage.response(requestId, (byte) 1, Status.OK, Map.of(), Unpooled.buffer(4).writeInt(7)));

		RpcMessage response = future.get(1, TimeUnit.SECONDS);
		assertEquals(requestId, response.requestId());
		assertEquals(1, response.refCnt(), "the waiter owns the response and must release it");
		response.release();
		assertEquals(0, pending.size());
	}

	@Test
	void doesNotBlockCallerThreadBeforeCompletion() {
		PendingRequests pending = new PendingRequests();
		CompletableFuture<RpcMessage> future = pending.register(pending.nextRequestId(), 5, TimeUnit.SECONDS);
		// 登记并读取句柄不应阻塞，无论此时是否已经有响应到达
		assertFalse(future.isDone());
		assertEquals(1, pending.size());
	}

	@Test
	void timesOutWithoutAReplyAndLeavesNoEntryBehind() {
		PendingRequests pending = new PendingRequests();
		CompletableFuture<RpcMessage> future = pending.register(pending.nextRequestId(), 200, TimeUnit.MILLISECONDS);

		CompletionException ex = assertThrows(CompletionException.class, future::join);
		assertTrue(PendingRequests.isTimeout(ex.getCause()) || PendingRequests.isTimeout(ex));
		await().atMost(1, TimeUnit.SECONDS).until(() -> pending.size() == 0);
	}

	@Test
	void unmatchedReplyIsReleasedSafely() {
		// 对没有任何调用方在等待的 requestId 调用 complete()：不抛异常，并释放这条迟到响应的缓冲区
		RpcMessage late = RpcMessage.response(999_999_999L, (byte) 1, Status.OK, Map.of(), Unpooled.buffer(4).writeInt(7));
		new PendingRequests().complete(late);
		assertEquals(0, late.refCnt());
	}

	@Test
	void replyArrivingAfterTimeoutIsReleased() {
		PendingRequests pending = new PendingRequests();
		long requestId = pending.nextRequestId();
		CompletableFuture<RpcMessage> future = pending.register(requestId, 50, TimeUnit.MILLISECONDS);
		assertThrows(CompletionException.class, future::join);

		RpcMessage late = RpcMessage.response(requestId, (byte) 1, Status.OK, Map.of(), Unpooled.buffer(4).writeInt(7));
		pending.complete(late);
		assertEquals(0, late.refCnt(), "a response for a timed-out request must not leak");
		assertEquals(0, pending.size());
	}

	@Test
	void requestIdsArePerInstance() {
		// 每个客户端各自编号：两个实例互不推进对方的序列
		PendingRequests a = new PendingRequests();
		PendingRequests b = new PendingRequests();
		assertEquals(a.nextRequestId(), b.nextRequestId());
		assertNotEquals(a.nextRequestId(), a.nextRequestId());
	}

	@Test
	void failAllCompletesEveryWaiterExceptionally() {
		PendingRequests pending = new PendingRequests();
		CompletableFuture<RpcMessage> first = pending.register(pending.nextRequestId(), 5, TimeUnit.SECONDS);
		CompletableFuture<RpcMessage> second = pending.register(pending.nextRequestId(), 5, TimeUnit.SECONDS);

		pending.failAll(new RpcException("closed"));

		assertInstanceOf(RpcException.class, assertThrows(CompletionException.class, first::join).getCause());
		assertInstanceOf(RpcException.class, assertThrows(CompletionException.class, second::join).getCause());
		assertEquals(0, pending.size());
	}
}
