package com.feynman.rpc.it;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * A0-8 的全仓部分：所有生产代码（本模块依赖了全部实现模块，测试类排除）不得调用 {@code System.setProperty}
 * 或 {@code System.getProperties()}。配置只能经不可变的 RpcClientConfig / RpcServerConfig 进入框架——
 * 旧 starter 把 properties 写进 System 属性再由 core 读回，同 JVM 两个上下文会互相覆盖。
 */
@AnalyzeClasses(packages = "com.feynman.rpc", importOptions = ImportOption.DoNotIncludeTests.class)
class NoSystemPropertiesArchTest {

	@ArchTest
	static final ArchRule no_global_system_property_access = noClasses()
			.should().callMethod(System.class, "setProperty", String.class, String.class)
			.orShould().callMethod(System.class, "getProperties")
			.because("configuration must flow through immutable RpcClientConfig / RpcServerConfig (DESIGN 3.3)");

	/** 防止规则因为导入范围不对而空转通过：确认每个生产模块都在被检查的类集合里。 */
	@ArchTest
	static void importCoversEveryProductionModule(JavaClasses classes) {
		for (String name : List.of(
				"com.feynman.rpc.core.RpcClient",
				"com.feynman.rpc.core.RpcServer",
				"com.feynman.rpc.registry.zookeeper.ZookeeperRegistry",
				"com.feynman.rpc.registry.nacos.NacosRegistry",
				"com.feynman.rpc.serialize.fastjson2.Fastjson2Serializer",
				"com.feynman.rpc.serialize.protobuf.ProtobufSerializer",
				"com.feynman.rpc.starter.RpcAutoConfiguration",
				"com.feynman.rpc.examples.provider.ProviderApplication")) {
			assertTrue(classes.contain(name), "production class not imported: " + name);
		}
		assertTrue(classes.stream().noneMatch(c -> c.getName().startsWith("com.feynman.rpc.it.")), "test classes must be excluded");
	}
}
