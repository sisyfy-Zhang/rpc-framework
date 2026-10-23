package io.github.sisyfyzhang.steadyrpc.serialize.archfixture;

import java.util.HashMap;
import java.util.Map;

/**
 * ExtensionModuleArchitectureRuleSelfTest 的夹具：序列化模块包名下的进程级 schema 缓存（Protostuff 全局 IdStrategy
 * 被污染的那类问题），扩展后的规则应当拦下它。
 */
public class LegacySerializerStatics {

	static final Map<Class<?>, Object> SCHEMA_CACHE = new HashMap<>();
}
