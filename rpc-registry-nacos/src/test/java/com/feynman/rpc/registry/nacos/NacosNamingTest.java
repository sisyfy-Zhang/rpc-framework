package com.feynman.rpc.registry.nacos;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceMetadata;
import com.feynman.rpc.core.registry.ServiceInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯映射逻辑测试，不需要 Nacos 服务端；与真实服务端的交互见门控的 {@code NacosRegistryTest}。 */
class NacosNamingTest {

	private static final ServiceKey KEY = new ServiceKey("com.example.UserFacade", "1.0", "gray");

	@Test
	void groupIsOurGroupAndServiceNameCarriesTheVersion() {
		assertEquals("gray", NacosNaming.groupName(KEY));
		assertEquals("com.example.UserFacade:1.0", NacosNaming.serviceName(KEY));
		assertEquals("com.example.Foo:", NacosNaming.serviceName(new ServiceKey("com.example.Foo", null, null)));
		assertEquals("default", NacosNaming.groupName(new ServiceKey("com.example.Foo", null, null)));
	}

	@Test
	void instanceRoundTripsThroughNacosModel() {
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(InstanceMetadata.WEIGHT, "3");
		metadata.put(InstanceMetadata.PROTOCOL, "1");
		metadata.put(InstanceMetadata.PID, "42");
		ServiceInstance ours = new ServiceInstance(KEY, "10.0.0.1", 9000, metadata);

		Instance nacos = NacosNaming.toNacos(ours);
		assertEquals("10.0.0.1", nacos.getIp());
		assertEquals(9000, nacos.getPort());
		assertEquals(3.0, nacos.getWeight());
		assertTrue(nacos.isEphemeral());
		assertEquals(metadata, nacos.getMetadata());

		assertEquals(ours, NacosNaming.fromNacos(KEY, nacos));
	}
}
