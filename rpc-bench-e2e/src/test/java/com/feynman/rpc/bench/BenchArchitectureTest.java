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

	/**
	 * 发压的调用方是虚拟线程：harness 里不是 ChannelHandler 的代码不碰 Netty 的池化分配器（虚拟线程无线程缓存、
	 * 竞争 arena 锁、扩容时钉住载体线程，docs/pitfalls.md 第 32 条）。请求体用 Unpooled 包装现成的 byte[]，
	 * 其余缓冲区由编码器在事件循环上分配。
	 */
	@ArchTest
	static final ArchRule callers_do_not_touch_netty_allocator = noClasses()
			.that().areNotAssignableTo("io.netty.channel.ChannelHandler")
			.should().callMethodWhere(com.tngtech.archunit.base.DescribedPredicate.describe("obtain or use a Netty ByteBufAllocator",
					call -> call.getTargetOwner().isAssignableTo("io.netty.buffer.ByteBufAllocator")
							|| ("alloc".equals(call.getName()) && (call.getTargetOwner().isAssignableTo("io.netty.channel.Channel")
									|| call.getTargetOwner().isAssignableTo("io.netty.channel.ChannelHandlerContext")))))
			.because("load-generating virtual threads must not allocate from Netty's pooled allocator (docs/pitfalls.md #32)");

	@ArchTest
	static void importCoversTheHarness(JavaClasses classes) {
		assertTrue(classes.contain(Bench.class.getName()));
		assertTrue(classes.contain(LoadDriver.class.getName()));
		assertTrue(classes.stream().noneMatch(c -> c.getSimpleName().endsWith("Test")), "test classes must be excluded");
	}
}
