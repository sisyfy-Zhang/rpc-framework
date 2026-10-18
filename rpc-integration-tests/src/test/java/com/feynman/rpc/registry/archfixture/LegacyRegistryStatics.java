package com.feynman.rpc.registry.archfixture;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ExtensionModuleArchitectureRuleSelfTest 的夹具：放在注册中心模块的包名下，模仿旧实现里出现过的静态状态
 * （进程级单例注册表、全局计数器、可变单例字段），证明扩展后的规则在这些包里会拦下它们。只在测试源码里，
 * 不会被主规则（DoNotIncludeTests）导入。
 */
public class LegacyRegistryStatics {

	static final Logger log = LoggerFactory.getLogger(LegacyRegistryStatics.class);

	static final Map<String, Object> SHARED_REGISTRIES = new ConcurrentHashMap<>();

	static final AtomicInteger COUNTER = new AtomicInteger();

	static LegacyRegistryStatics instance;

	static final int CONSTANT = 3;
}
