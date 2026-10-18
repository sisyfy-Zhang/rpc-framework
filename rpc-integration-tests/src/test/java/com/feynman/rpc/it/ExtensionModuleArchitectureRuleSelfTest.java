package com.feynman.rpc.it;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.registry.archfixture.LegacyRegistryStatics;
import com.feynman.rpc.registry.zookeeper.ZookeeperRegistry;
import com.feynman.rpc.serialize.archfixture.LegacySerializerStatics;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;

/**
 * 规则必须能失败：{@link ExtensionModuleArchitectureTest} 的两条规则对注册中心 / 序列化模块包里的静态容器、
 * 全局计数器、可变单例字段报违规；不误伤 Logger 与基本类型常量；白名单只放行 ZookeeperRegistry.INSTANCE_IDS 那一个字段。
 */
class ExtensionModuleArchitectureRuleSelfTest {

	@Test
	void staticContainersCountersAndSingletonsInExtensionPackagesAreRejected() {
		JavaClasses classes = new ClassFileImporter().importClasses(LegacyRegistryStatics.class, LegacySerializerStatics.class);
		EvaluationResult containers = ExtensionModuleArchitectureTest.no_static_mutable_containers.evaluate(classes);
		String report = containers.getFailureReport().toString();
		assertTrue(containers.hasViolation());
		assertTrue(report.contains("SHARED_REGISTRIES") && report.contains("COUNTER") && report.contains("SCHEMA_CACHE"), report);
		assertFalse(report.contains("LegacyRegistryStatics.log>") || report.contains("CONSTANT"), "Logger and constants are fine: " + report);

		EvaluationResult nonFinal = ExtensionModuleArchitectureTest.no_static_non_final_fields.evaluate(classes);
		assertTrue(nonFinal.hasViolation());
		assertTrue(nonFinal.getFailureReport().toString().contains("instance"), nonFinal.getFailureReport().toString());
	}

	@Test
	void theWhitelistAdmitsExactlyTheZookeeperInstanceCounter() {
		JavaClasses classes = new ClassFileImporter().importClasses(ZookeeperRegistry.class);
		assertFalse(ExtensionModuleArchitectureTest.no_static_mutable_containers.evaluate(classes).hasViolation(),
				"ZookeeperRegistry.INSTANCE_IDS is the whitelisted exception");
		// 对照：去掉白名单，同一个字段就会被拦下——白名单确实在起作用，而不是规则没看到它
		ArchRule withoutWhitelist = noFields()
				.that(ExtensionModuleArchitectureTest.STATIC_FINAL_NON_SYNTHETIC)
				.and().areDeclaredInClassesThat(ExtensionModuleArchitectureTest.IN_EXTENSION_MODULES)
				.should().haveRawType(ExtensionModuleArchitectureTest.MUTABLE_STATE_TYPE);
		EvaluationResult result = withoutWhitelist.evaluate(classes);
		assertTrue(result.hasViolation() && result.getFailureReport().toString().contains("INSTANCE_IDS"),
				result.getFailureReport().toString());
	}
}
