package com.feynman.rpc.core.server;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.internal.InFlightGate;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.serialize.Serializers;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import io.netty.channel.embedded.EmbeddedChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@link ServerHandler} 处理 NEGOTIATE_REQUEST 时确实遵守客户端的优先级顺序（而不只是
 * "是否支持"），并且在客户端列表完全没有交集时会退回本服务端配置的序列化器。用测试模块自己的
 * {@code META-INF/rpc/...Serializer} 配置的两个实现（code 10 / 20），因为 rpc-core 本身按设计在
 * classpath 上没有真实的 Serializer 实现。
 */
class NegotiationTest {

	private static ServerHandler handler(byte fallbackCode) {
		// 协商不经过业务执行器：执行器不会收到任务，也就不会起线程
		return new ServerHandler(new ServiceDispatcher(List.of(), Serializers.load(SimpleTestSerializer.NAME, Map.of()), true),
				new BusinessExecutor(ExecutorConfig.fixed(1)), new InFlightGate(), fallbackCode);
	}

	@Test
	void picksClientsMostPreferredCommonSerializer() {
		EmbeddedChannel channel = new EmbeddedChannel(handler((byte) 10));

		// 客户端优先选 20 而不是 10；服务端两个都支持 -> 必须选 20
		channel.writeInbound(RpcMessage.negotiateRequest(new byte[] { 20, 10 }));

		RpcMessage response = channel.readOutbound();
		try {
			assertEquals(MessageType.NEGOTIATE_RESPONSE, response.messageType());
			assertEquals(1, response.body().readableBytes());
			assertEquals((byte) 20, response.body().getByte(0));
		} finally {
			response.release();
		}
	}

	@Test
	void fallsBackToServerConfiguredSerializerWhenNoOverlap() {
		EmbeddedChannel channel = new EmbeddedChannel(handler((byte) 20));

		// 客户端只知道一个服务端从没听说过的编号（99）
		channel.writeInbound(RpcMessage.negotiateRequest(new byte[] { 99 }));

		RpcMessage response = channel.readOutbound();
		try {
			assertEquals((byte) 20, response.body().getByte(0));
		} finally {
			response.release();
		}
	}
}
