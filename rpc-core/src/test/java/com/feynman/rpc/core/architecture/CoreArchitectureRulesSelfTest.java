package com.feynman.rpc.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 规则必须能失败才算规则：把旧实现里出现过的几种静态状态写成夹具类，证明 {@link CoreArchitectureTest}
 * 的规则会拦下它们，同时不误伤常量、Logger 风格字段与枚举。
 */
class CoreArchitectureRulesSelfTest {

	static class LegacyStyleStatics {
		static final Map<Long, Object> PENDING = new ConcurrentHashMap<>();
		static final AtomicLong REQUEST_ID = new AtomicLong();
		static final String[] NAMES = { "a" };
	}

	static class LegacySingleton {
		static LegacySingleton instance;
	}

	static class Harmless {
		static final int MAX = 3;
		static final String NAME = "x";
		static final List<String> NOT_REALLY_CONSTANT = List.of("a");
	}

	enum SomeEnum {
		A, B
	}

	private static final ArchRule STATIC_MUST_BE_FINAL = fields()
			.that().areDeclaredInClassesThat().resideInAPackage("com.feynman.rpc.core..")
			.and().areStatic()
			.should().beFinal();

	private static final ArchRule NO_STATIC_CONTAINERS = noFields()
			.that(CoreArchitectureRules.STATIC_FINAL_NON_SYNTHETIC)
			.and().areDeclaredInClassesThat(CoreArchitectureRules.IN_CORE_AND_NOT_WHITELISTED)
			.should().haveRawType(CoreArchitectureRules.MUTABLE_STATE_TYPE)
			.allowEmptyShould(true);

	@Test
	void staticContainersAndArraysAreRejected() {
		JavaClasses classes = new ClassFileImporter().importClasses(LegacyStyleStatics.class);
		var result = NO_STATIC_CONTAINERS.evaluate(classes);
		assertTrue(result.hasViolation());
		String report = result.getFailureReport().toString();
		assertTrue(report.contains("PENDING") && report.contains("REQUEST_ID") && report.contains("NAMES"), report);
	}

	@Test
	void mutableSingletonFieldIsRejected() {
		JavaClasses classes = new ClassFileImporter().importClasses(LegacySingleton.class);
		assertTrue(STATIC_MUST_BE_FINAL.evaluate(classes).hasViolation());
	}

	@Test
	void constantsAndEnumsPass() {
		// 已知取舍：List.of(...) 这样的不可变集合常量也会被拦下（类型层面无法区分可变与否），需要时进白名单并写明理由
		JavaClasses enums = new ClassFileImporter().importClasses(SomeEnum.class);
		assertFalse(NO_STATIC_CONTAINERS.evaluate(enums).hasViolation(), "enum $VALUES is synthetic and must be ignored");
		JavaClasses harmless = new ClassFileImporter().importClasses(Harmless.class);
		String report = NO_STATIC_CONTAINERS.evaluate(harmless).getFailureReport().toString();
		assertFalse(report.contains("Harmless.MAX>") || report.contains("Harmless.NAME>"), report);
		assertTrue(report.contains("Harmless.NOT_REALLY_CONSTANT>"), report);
	}
}
