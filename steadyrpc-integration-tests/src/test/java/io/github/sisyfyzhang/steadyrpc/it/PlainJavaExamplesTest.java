package io.github.sisyfyzhang.steadyrpc.it;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.RpcClient;
import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.examples.api.InventoryService;
import io.github.sisyfyzhang.steadyrpc.examples.plain.PlainConsumer;
import io.github.sisyfyzhang.steadyrpc.examples.plain.PlainProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * README "纯 Java 快速开始"的代码就是 steadyrpc-examples 的 {@link PlainProvider} / {@link PlainConsumer}：这里对进程内 ZooKeeper
 * 跑一遍，保证文档里的片段真的能用。
 */
class PlainJavaExamplesTest {

	@Test
	void plainJavaProviderAndConsumerFromTheReadmeWork() throws Exception {
		try (ZkFixture zk = new ZkFixture()) {
			RpcServer server = PlainProvider.start(zk.connectString(), "127.0.0.1", 0);
			try {
				server.registered().get(10, TimeUnit.SECONDS);
				assertEquals(List.of("127.0.0.1:" + server.port()), zk.providers(ServiceKey.of(InventoryService.class)));
				try (RpcClient client = PlainConsumer.connect(zk.connectString())) {
					List<String> lines = PlainConsumer.demo(client);
					assertEquals("left=6", lines.get(0));
					assertEquals("buyers=[User{id=1, name=plain-1}]", lines.get(1));
					assertTrue(lines.get(2).startsWith("InsufficientStockException: sku 2001 has 6 left"), lines.get(2));
					assertEquals("protobuf", client.config().serializer());
				}
			} finally {
				server.close();
			}
			assertTrue(zk.providers(ServiceKey.of(InventoryService.class)).isEmpty());
		}
	}
}
