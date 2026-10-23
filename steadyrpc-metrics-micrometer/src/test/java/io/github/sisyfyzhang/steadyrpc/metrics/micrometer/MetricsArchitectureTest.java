package io.github.sisyfyzhang.steadyrpc.metrics.micrometer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 代码层护栏（依赖层面由本模块 pom 的 enforcer bannedDependencies 守）：指标模块给纯 Java 进程用，不依赖 Spring；
 * 只有 PrometheusSupport 一处引用 Prometheus registry 的类型（optional 依赖缺失时其余类照常加载）；不写全局 System 属性；
 * 没有 static 非 final 字段。无可变静态容器的规则由 steadyrpc-integration-tests 的 ExtensionModuleArchitectureTest 统一检查。
 */
@AnalyzeClasses(packages = "io.github.sisyfyzhang.steadyrpc.metrics", importOptions = ImportOption.DoNotIncludeTests.class)
class MetricsArchitectureTest {

	@ArchTest
	static final ArchRule no_spring = noClasses()
			.should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
			.because("steadyrpc-metrics-micrometer is container-free; Spring wiring lives in steadyrpc-spring-boot-starter");

	@ArchTest
	static final ArchRule prometheus_types_only_in_prometheus_support = noClasses()
			.that().doNotHaveSimpleName("PrometheusSupport")
			.should().dependOnClassesThat().resideInAnyPackage("io.micrometer.prometheusmetrics..", "io.prometheus..")
			.because("micrometer-registry-prometheus is optional: only PrometheusSupport may link against it");

	@ArchTest
	static final ArchRule no_global_system_property_writes = noClasses()
			.should().callMethod(System.class, "setProperty", String.class, String.class)
			.orShould().callMethod(System.class, "getProperties")
			.because("configuration flows through constructor arguments");

	@ArchTest
	static final ArchRule no_static_non_final_fields = fields().that().areStatic().should().beFinal();

	@ArchTest
	static void importCoversTheModule(JavaClasses classes) {
		assertTrue(classes.contain(MicrometerClientFilter.class.getName()));
		assertTrue(classes.contain(PrometheusEndpoint.class.getName()));
		assertTrue(classes.stream().noneMatch(c -> c.getSimpleName().endsWith("Test")), "test classes must be excluded");
	}
}
