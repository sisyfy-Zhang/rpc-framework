package io.github.sisyfyzhang.steadyrpc.core.registry;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceModelTest {

	@Test
	void serviceKeyDefaultsAndStringForm() {
		ServiceKey key = new ServiceKey("com.example.Foo", null, null);
		assertEquals("", key.version());
		assertEquals("default", key.group());
		assertEquals("com.example.Foo::default", key.toString());
		assertEquals(key, ServiceKey.parse(key.toString()));
		assertEquals("com.example.Foo:1.0:gray", new ServiceKey("com.example.Foo", "1.0", "gray").toString());
	}

	@Test
	void serviceKeyRejectsSeparatorsInParts() {
		assertThrows(IllegalArgumentException.class, () -> new ServiceKey("a:b", "", ""));
		assertThrows(IllegalArgumentException.class, () -> new ServiceKey("a", "1/2", ""));
		assertThrows(IllegalArgumentException.class, () -> ServiceKey.parse("only:two"));
	}

	@Test
	void metadataQueryStringRoundTripsInOrder() {
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(InstanceMetadata.WEIGHT, "3");
		metadata.put(InstanceMetadata.PROTOCOL, "1");
		metadata.put(InstanceMetadata.TIMESTAMP, "1700000000000");
		metadata.put(InstanceMetadata.PID, "4242");
		metadata.put("tag", "a&b=c");

		String encoded = InstanceMetadata.encode(metadata);

		assertEquals("weight=3&proto=1&ts=1700000000000&pid=4242&tag=a%26b%3Dc", encoded);
		assertEquals(metadata, InstanceMetadata.decode(encoded));
	}

	@Test
	void instanceWeightFallsBackToOne() {
		ServiceKey key = ServiceKey.of(Runnable.class);
		assertEquals(1, new ServiceInstance(key, "h", 1, Map.of()).weight());
		assertEquals(1, new ServiceInstance(key, "h", 1, Map.of(InstanceMetadata.WEIGHT, "x")).weight());
		assertEquals(5, new ServiceInstance(key, "h", 1, Map.of(InstanceMetadata.WEIGHT, "5")).weight());
		assertEquals("h:1", new ServiceInstance(key, "h", 1, null).address());
	}

	@Test
	void registryConfigFactoriesAndProperties() {
		RegistryConfig zk = RegistryConfig.zookeeper("127.0.0.1:2181").withProperty("session-timeout-ms", "3000");
		assertEquals("zookeeper", zk.type());
		assertEquals("3000", zk.property("session-timeout-ms", "60000"));
		assertEquals("60000", zk.property("missing", "60000"));
		assertEquals("nacos", RegistryConfig.nacos("127.0.0.1:8848").type());
		assertThrows(IllegalArgumentException.class, () -> RegistryConfig.zookeeper(" "));
	}
}
