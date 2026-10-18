package com.feynman.rpc.core.architecture;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.feynman.rpc.core.RpcContext;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;

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
			"reside in com.feynman.rpc.core.. and are not whitelisted (ExtensionLoader, RpcContext)",
			clazz -> clazz.getPackageName().startsWith("com.feynman.rpc.core")
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

	private CoreArchitectureRules() {
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
