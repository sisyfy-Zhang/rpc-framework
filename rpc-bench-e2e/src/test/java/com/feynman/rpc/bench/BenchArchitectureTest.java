package com.feynman.rpc.bench;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * harness 的代码层护栏（依赖层面由本模块 pom 的 enforcer bannedDependencies 守）：
 * 不依赖 Spring / Jackson / picocli / Lombok，不改全局 System 属性（与 rpc-integration-tests 的
 * NoSystemPropertiesArchTest 同一条规则——那里的导入范围不含本模块，因为集成测试模块不依赖 harness）。
 */
@AnalyzeClasses(packages = "com.feynman.rpc.bench", importOptions = ImportOption.DoNotIncludeTests.class)
class BenchArchitectureTest {

	@ArchTest
	static final ArchRule no_banned_libraries = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.fasterxml.jackson..", "picocli..",
					"lombok..")
			.because("rpc-bench-e2e is a plain Java CLI (task 6 brief)");

	@ArchTest
	static final ArchRule no_global_system_property_writes = noClasses()
			.should().callMethod(System.class, "setProperty", String.class, String.class)
			.orShould().callMethod(System.class, "getProperties")
			.because("configuration flows through the profile card and immutable RpcClientConfig / RpcServerConfig");

	@ArchTest
	static void importCoversTheHarness(JavaClasses classes) {
		assertTrue(classes.contain(Bench.class.getName()));
		assertTrue(classes.contain(LoadDriver.class.getName()));
		assertTrue(classes.stream().noneMatch(c -> c.getSimpleName().endsWith("Test")), "test classes must be excluded");
	}
}
