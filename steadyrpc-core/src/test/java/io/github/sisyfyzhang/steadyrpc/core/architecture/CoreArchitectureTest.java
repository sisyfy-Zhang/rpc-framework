package io.github.sisyfyzhang.steadyrpc.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * A0-8 的 core 部分：core 不依赖 Spring；core 没有可变静态状态（白名单见 {@link CoreArchitectureRules}）；
 * 调用方一侧与服务端业务线程上的代码不碰 Netty 池化分配器。
 * 规则本身定义在 {@link CoreArchitectureRules}，这里只把它们挂到 ArchUnit 的 JUnit 5 引擎上；
 * {@code CoreArchitectureRulesSelfTest} 证明这些规则对违规代码确实会失败。
 */
@AnalyzeClasses(packages = "io.github.sisyfyzhang.steadyrpc.core", importOptions = ImportOption.DoNotIncludeTests.class)
class CoreArchitectureTest {

	@ArchTest
	static final ArchRule core_does_not_depend_on_spring = noClasses()
			.that().resideInAPackage("io.github.sisyfyzhang.steadyrpc.core..")
			.should().dependOnClassesThat().resideInAPackage("org.springframework..");

	@ArchTest
	static final ArchRule no_static_non_final_fields = fields()
			.that().areDeclaredInClassesThat().resideInAPackage("io.github.sisyfyzhang.steadyrpc.core..")
			.and().areStatic()
			.should().beFinal()
			.because("mutable process-wide state makes it impossible to run two clients/servers in one JVM");

	@ArchTest
	static final ArchRule no_static_mutable_containers = noFields()
			.that(CoreArchitectureRules.STATIC_FINAL_NON_SYNTHETIC)
			.and().areDeclaredInClassesThat(CoreArchitectureRules.IN_CORE_AND_NOT_WHITELISTED)
			.should().haveRawType(CoreArchitectureRules.MUTABLE_STATE_TYPE)
			.because("static containers are global mutable state; see CoreArchitectureRules for the whitelist and why");

	@ArchTest
	static final ArchRule caller_side_does_not_touch_netty_allocator =
			CoreArchitectureRules.callerSideMustNotTouchNettyAllocator(CoreArchitectureRules.CALLER_SIDE);
}
