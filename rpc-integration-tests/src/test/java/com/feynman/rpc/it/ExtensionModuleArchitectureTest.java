package com.feynman.rpc.it;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * A0-8 的扩展：把 rpc-core 的"无 static 非 final 字段 / 无可变静态容器"两条规则扩展到两个注册中心模块
 * （rpc-registry-zookeeper、rpc-registry-nacos）、两个序列化模块（rpc-serialize-protobuf、rpc-serialize-fastjson2，
 * 含放在 {@code io.protostuff.runtime} 包里的 AllowlistIdStrategy 副本）与指标模块（rpc-metrics-micrometer，
 * {@code com.feynman.rpc.metrics}）。为什么在这里统一挂：本模块依赖了全部实现模块，
 * 不需要给每个模块单独加 ArchUnit 依赖；规则与 core 的同义（谓词在本类重新声明——core 的规则类在 core 的 test 源码里，
 * 不随 jar 发布）。
 *
 * <p>白名单（字段级，比 core 的类级白名单更窄）：
 * <ul>
 *   <li>{@code ZookeeperRegistry.INSTANCE_IDS}（AtomicInteger）：进程内实例序号，只用于线程命名
 *       {@code rpc-registry-zk-{id}-*}。不承载业务状态，两个实例共享它不会互相影响；而线程名必须跨实例唯一，
 *       "关闭后无残留线程"的断言才能按实例前缀进行（D-010 已接受的刻意例外）。</li>
 *   <li>Logger：不是容器类型，规则本来就不拦，无需列入。</li>
 * </ul>
 * {@code ExtensionModuleArchitectureRuleSelfTest} 证明这两条规则对这些包里的违规代码确实会失败、白名单只放行那一个字段。
 */
@AnalyzeClasses(packages = { "com.feynman.rpc.registry", "com.feynman.rpc.serialize", "com.feynman.rpc.metrics", "io.protostuff.runtime" },
		importOptions = ImportOption.DoNotIncludeTests.class)
class ExtensionModuleArchitectureTest {

	static final String WHITELISTED_FIELD = "com.feynman.rpc.registry.zookeeper.ZookeeperRegistry.INSTANCE_IDS";

	/** 被检查的类：两个注册中心模块、两个序列化模块，以及 io.protostuff.runtime 里我们自己的那个副本（不含 Protostuff 库本身）。 */
	static final DescribedPredicate<JavaClass> IN_EXTENSION_MODULES = DescribedPredicate.describe(
			"belong to rpc-registry-zookeeper / rpc-registry-nacos / rpc-serialize-protobuf / rpc-serialize-fastjson2 / rpc-metrics-micrometer",
			clazz -> clazz.getName().startsWith("com.feynman.rpc.registry.")
					|| clazz.getName().startsWith("com.feynman.rpc.serialize.")
					|| clazz.getName().startsWith("com.feynman.rpc.metrics.")
					|| clazz.getName().equals("io.protostuff.runtime.AllowlistIdStrategy")
					|| clazz.getName().startsWith("io.protostuff.runtime.AllowlistIdStrategy$"));

	static final DescribedPredicate<JavaField> STATIC_FINAL_NON_SYNTHETIC = DescribedPredicate.describe(
			"are static final and not compiler-generated",
			field -> field.getModifiers().contains(JavaModifier.STATIC)
					&& field.getModifiers().contains(JavaModifier.FINAL)
					&& !field.getModifiers().contains(JavaModifier.SYNTHETIC));

	static final DescribedPredicate<JavaField> NOT_WHITELISTED = DescribedPredicate.describe(
			"are not whitelisted (" + WHITELISTED_FIELD + ")",
			field -> !field.getFullName().equals(WHITELISTED_FIELD));

	static final DescribedPredicate<JavaClass> MUTABLE_STATE_TYPE = DescribedPredicate.describe(
			"a mutable container or resource type (Map, Collection, java.util.concurrent.atomic.*, array, ThreadLocal, "
					+ "ExecutorService, Netty EventLoopGroup / Bootstrap)",
			type -> type.isArray()
					|| type.isAssignableTo(Map.class)
					|| type.isAssignableTo(Collection.class)
					|| type.getPackageName().equals("java.util.concurrent.atomic")
					|| type.isAssignableTo(ThreadLocal.class)
					|| type.isAssignableTo(ExecutorService.class)
					|| type.isAssignableTo("io.netty.channel.EventLoopGroup")
					|| type.isAssignableTo("io.netty.bootstrap.AbstractBootstrap"));

	@ArchTest
	static final ArchRule no_static_non_final_fields = fields()
			.that().areDeclaredInClassesThat(IN_EXTENSION_MODULES)
			.and().areStatic()
			.should().beFinal()
			.because("mutable process-wide state makes it impossible to run two registries / serializers in one JVM");

	@ArchTest
	static final ArchRule no_static_mutable_containers = noFields()
			.that(STATIC_FINAL_NON_SYNTHETIC)
			.and().areDeclaredInClassesThat(IN_EXTENSION_MODULES)
			.and(NOT_WHITELISTED)
			.should().haveRawType(MUTABLE_STATE_TYPE)
			.because("static containers are global mutable state; see ExtensionModuleArchitectureTest for the whitelist and why");

	/** 规则不能因为导入范围不对而空转：四个模块与协议副本都在被检查的类集合里，白名单字段也确实存在（白名单没有过期）。 */
	@ArchTest
	static void importCoversEveryExtensionModuleAndTheWhitelistIsNotStale(JavaClasses classes) {
		for (String name : List.of(
				"com.feynman.rpc.registry.zookeeper.ZookeeperRegistry",
				"com.feynman.rpc.registry.nacos.NacosRegistry",
				"com.feynman.rpc.serialize.protobuf.ProtobufSerializer",
				"com.feynman.rpc.serialize.protobuf.TypeSchemas",
				"com.feynman.rpc.serialize.fastjson2.Fastjson2Serializer",
				"com.feynman.rpc.metrics.micrometer.MicrometerServerFilter",
				"com.feynman.rpc.metrics.micrometer.PrometheusEndpoint",
				"io.protostuff.runtime.AllowlistIdStrategy")) {
			assertTrue(classes.contain(name), "production class not imported: " + name);
		}
		Set<JavaField> fields = classes.get("com.feynman.rpc.registry.zookeeper.ZookeeperRegistry").getFields();
		assertTrue(fields.stream().anyMatch(f -> f.getFullName().equals(WHITELISTED_FIELD)), "whitelisted field no longer exists");
		assertTrue(classes.stream().noneMatch(c -> c.getName().startsWith("com.feynman.rpc.it.")), "test classes must be excluded");
	}
}
