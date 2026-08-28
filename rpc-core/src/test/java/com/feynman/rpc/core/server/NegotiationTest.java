package com.feynman.rpc.core.server;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;

import io.netty.channel.embedded.EmbeddedChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@link ServerHandler} 处理 NEGOTIATE_REQUEST 时确实遵守客户端的优先级顺序（而不只是
 * "是否支持"），并且在客户端列表完全没有交集时会退回本服务端自己的默认序列化器。用
 * {@link FakeSerializerA}/{@link FakeSerializerB}（通过本测试模块自己的
 * {@code META-INF/rpc/...Serializer} 配置）是因为 rpc-core 本身按设计在 classpath 上
 * 没有真实的 Serializer 实现。
 */
class NegotiationTest {

	@Test
	void picksClientsMostPreferredCommonSerializer() {
		EmbeddedChannel channel = new EmbeddedChannel(new ServerHandler());

		// 客户端优先选 B(20) 而不是 A(10)；服务端两个都支持 -> 必须选 B
		RpcMessage request = new RpcMessage(0, MessageType.NEGOTIATE_REQUEST, (byte) 0, Status.OK,
				new byte[] { 20, 10 });
		channel.writeInbound(request);

		RpcMessage response = channel.readOutbound();
		assertEquals(MessageType.NEGOTIATE_RESPONSE, response.getMessageType());
		assertEquals(1, response.getPayload().length);
		assertEquals((byte) 20, response.getPayload()[0]);
	}

	@Test
	void fallsBackToServerDefaultWhenNoOverlap() {
		EmbeddedChannel channel = new EmbeddedChannel(new ServerHandler());

		// 客户端只知道一个服务端从没听说过的编号（99）
		RpcMessage request = new RpcMessage(0, MessageType.NEGOTIATE_REQUEST, (byte) 0, Status.OK,
				new byte[] { 99 });
		channel.writeInbound(request);

		RpcMessage response = channel.readOutbound();
		assertEquals((byte) 10, response.getPayload()[0]); // FakeSerializerA 就是名为 "fastjson2" 的默认实现
	}
}
