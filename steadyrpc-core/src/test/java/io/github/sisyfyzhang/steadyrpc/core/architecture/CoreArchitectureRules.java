package io.github.sisyfyzhang.steadyrpc.core.architecture;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import io.github.sisyfyzhang.steadyrpc.core.RpcContext;
import io.github.sisyfyzhang.steadyrpc.core.extension.ExtensionLoader;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * "无可变静态状态"规则的谓词与白名单。
 *
 * <p>白名单（类级静态可变状态被允许的类，嵌套类一并放行）：
 * <ul>
 *   <li>{@link ExtensionLoader}：LOADERS 与每个 loader 的类定义 / 无状态单例缓存。缓存的是"类路径上有什么"，
 *       与任何 RpcClient / RpcServer 实例无关，重复加载只会浪费。</li>
 *   <li>{@link RpcContext}：调用方附加 attachments 的 ThreadLocal。ThreadLocal 本来就是"每线程一份"，
 *       没有可以实例化的归属对象；且发起调用即被消费清空。</li>
 * </ul>
 * Logger 与基本类型 / String 常量不属于"可变容器"，不需要白名单。
 * （协议 v2 起 Serializers 改为每个 RpcClient / RpcServer 持有的实例——序列化器可以带配置——已移出白名单。）
 */
final class CoreArchitectureRules {

	static final DescribedPredicate<JavaField> STATIC_FINAL_NON_SYNTHETIC = DescribedPredicate.describe(
			"are static final and not compiler-generated",
			field -> field.getModifiers().contains(JavaModifier.STATIC)
					&& field.getModifiers().contains(JavaModifier.FINAL)
					&& !field.getModifiers().contains(JavaModifier.SYNTHETIC));

	static final DescribedPredicate<JavaClass> IN_CORE_AND_NOT_WHITELISTED = DescribedPredicate.describe(
			"reside in io.github.sisyfyzhang.steadyrpc.core.. and are not whitelisted (ExtensionLoader, RpcContext)",
			clazz -> clazz.getPackageName().startsWith("io.github.sisyfyzhang.steadyrpc.core")
					&& !isWhitelisted(clazz));

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

	/**
	 * 不在事件循环上运行的代码：RpcClient / ReferenceProxy 所在的根包与 client、cluster、filter、loadbalance 包（调用方线程），
	 * 以及 server 包（ServiceDispatcher、BusinessExecutor、ServerHandler.DispatchTask 等跑在业务线程上，业务执行器可以是
	 * 虚拟线程）里，不是 Netty ChannelHandler 的类。ChannelHandler（ClientHandler、ServerHandler、编解码器）运行在事件循环上。
	 */
	static final DescribedPredicate<JavaClass> CALLER_SIDE = DescribedPredicate.describe(
			"run on caller or business threads (core root, client, cluster, filter, loadbalance, server packages; "
					+ "not a Netty ChannelHandler)",
			clazz -> isCallerSidePackage(clazz.getPackageName())
					&& !clazz.isAssignableTo("io.netty.channel.ChannelHandler"));

	/** 取得或使用 Netty 的 ByteBufAllocator：调用分配器的任何方法、{@code Channel.alloc()}、{@code ChannelHandlerContext.alloc()}、{@code ChannelConfig.getAllocator()}。 */
	static final DescribedPredicate<JavaMethodCall> TOUCHES_NETTY_ALLOCATOR = DescribedPredicate.describe(
			"obtain or use a Netty ByteBufAllocator",
			call -> {
				JavaClass owner = call.getTargetOwner();
				String name = call.getName();
				return owner.isAssignableTo("io.netty.buffer.ByteBufAllocator")
						|| ("alloc".equals(name) && (owner.isAssignableTo("io.netty.channel.Channel")
								|| owner.isAssignableTo("io.netty.channel.ChannelHandlerContext")))
						|| ("getAllocator".equals(name) && owner.isAssignableTo("io.netty.channel.ChannelConfig"));
			});

	/**
	 * 调用方线程与服务端业务线程不碰池化分配器：虚拟线程没有分配器的线程缓存、在 PoolArena
	 * 上竞争锁，{@code PoolArena.reallocate} 还会在 synchronized 内取锁、钉住载体线程。请求与回包都只在这些线程上序列化，
	 * 帧缓冲区由编码器在事件循环上分配。
	 */
	static ArchRule callerSideMustNotTouchNettyAllocator(DescribedPredicate<JavaClass> callerSide) {
		return noClasses().that(callerSide)
				.should().callMethodWhere(TOUCHES_NETTY_ALLOCATOR)
				.because("caller and business threads (often virtual threads) must not allocate from Netty's pooled allocator; "
						+ "the encoder allocates the frame on the event loop (see OutboundBody)");
	}

	private CoreArchitectureRules() {
	}

	private static boolean isCallerSidePackage(String pkg) {
		return pkg.equals("io.github.sisyfyzhang.steadyrpc.core")
				|| pkg.equals("io.github.sisyfyzhang.steadyrpc.core.client")
				|| pkg.equals("io.github.sisyfyzhang.steadyrpc.core.cluster")
				|| pkg.equals("io.github.sisyfyzhang.steadyrpc.core.filter")
				|| pkg.equals("io.github.sisyfyzhang.steadyrpc.core.loadbalance")
				|| pkg.equals("io.github.sisyfyzhang.steadyrpc.core.server");
	}

	private static boolean isWhitelisted(JavaClass clazz) {
		String name = clazz.getName();
		for (Class<?> allowed : new Class<?>[] { ExtensionLoader.class, RpcContext.class }) {
			if (name.equals(allowed.getName()) || name.startsWith(allowed.getName() + "$")) {
				return true;
			}
		}
		return false;
	}
}
