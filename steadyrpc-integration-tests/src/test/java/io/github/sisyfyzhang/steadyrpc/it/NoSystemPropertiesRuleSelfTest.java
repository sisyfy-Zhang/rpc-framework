package io.github.sisyfyzhang.steadyrpc.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/** 规则必须能失败：旧 starter 的写法（setProperty）与整表读取（getProperties）都会被拦下，单键读取不会。 */
class NoSystemPropertiesRuleSelfTest {

	static class LegacyStarterStyle {
		void apply(String address) {
			System.setProperty("rpc.registry.zookeeper.address", address);
		}
	}

	static class DumpsAllProperties {
		Properties dump() {
			return System.getProperties();
		}
	}

	static class ReadsOneKey {
		String read() {
			return System.getProperty("user.dir");
		}
	}

	@Test
	void setPropertyIsRejected() {
		JavaClasses classes = new ClassFileImporter().importClasses(LegacyStarterStyle.class);
		assertTrue(NoSystemPropertiesArchTest.no_global_system_property_access.evaluate(classes).hasViolation());
	}

	@Test
	void getPropertiesIsRejected() {
		JavaClasses classes = new ClassFileImporter().importClasses(DumpsAllProperties.class);
		assertTrue(NoSystemPropertiesArchTest.no_global_system_property_access.evaluate(classes).hasViolation());
	}

	@Test
	void singleKeyReadIsAllowed() {
		JavaClasses classes = new ClassFileImporter().importClasses(ReadsOneKey.class);
		assertFalse(NoSystemPropertiesArchTest.no_global_system_property_access.evaluate(classes).hasViolation());
	}
}
