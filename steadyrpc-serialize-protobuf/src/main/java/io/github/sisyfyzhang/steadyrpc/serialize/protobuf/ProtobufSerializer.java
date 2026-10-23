package io.github.sisyfyzhang.steadyrpc.serialize.protobuf;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializer;

import io.protostuff.runtime.AllowlistIdStrategy;

/**
 * 基于 Protostuff 运行时 schema 的 Protobuf 线上格式序列化器：不需要 .proto / 代码生成，就能处理任意 POJO 参数与返回值。
 *
 * <p>与 1.x 实现的三点不同（D-005，探针结论见 docs/probes/protostuff-1.8.0.md）：
 * <ol>
 *   <li><b>按声明类型构造 schema</b>（{@link TypeSchemas}）：{@code List<User>}、{@code Map<String, List<User>>}、
 *       {@code User[]}、byte[]、标量都有确定的编码，元素不写类名；绝不对 JDK 集合类调 RuntimeSchema。</li>
 *   <li><b>自有 IdStrategy</b>：每个实例一个 {@link AllowlistIdStrategy}，schema 缓存在其中，不读写全局
 *       {@code RuntimeEnv.ID_STRATEGY}——别处对全局策略的误用（探针 P2d）影响不到这里，这里也不会污染别人。</li>
 *   <li><b>多态位置的类名白名单</b>：Object / 接口 / 抽象类位置的值按线上类名加载，加载前校验包前缀。默认只允许
 *       {@link #defaultAllowedPrefixes()}；业务包用配置项 {@value #OPTION_ALLOWED_PACKAGES}（逗号分隔）追加。
 *       相比 autoType 黑名单，白名单默认拒绝未知类，新的利用链不需要追着补。</li>
 * </ol>
 *
 * <p>已知限制（写进接入文档）：不支持 record（D-005 P5）；字段演进需全部字段加 {@code @Tag} 或只在末尾追加；
 * 声明为具体非 final 父类的字段会丢子类型（P4c）。集合中的 null 元素保留，Map 的 null key / value 保留，
 * POJO 字段里的空集合与 null 可区分（见 {@link #STRATEGY_FLAGS}）。
 */
public class ProtobufSerializer implements Serializer {

	/** 追加多态白名单包前缀的配置键，值为逗号分隔的前缀，如 {@code com.acme.api.,com.acme.dto.}。 */
	public static final String OPTION_ALLOWED_PACKAGES = "protobuf.allowed-packages";

	/**
	 * 默认允许按名加载的包前缀：JDK 的集合、基础类型、时间与大数。做成方法而不是 {@code static final List} 常量：
	 * 全仓"无静态可变容器"的 ArchUnit 规则在类型层面无法区分不可变集合，方法每次返回新的不可变列表，不需要白名单。
	 */
	public static List<String> defaultAllowedPrefixes() {
		return List.of("java.util.", "java.lang.", "java.time.", "java.math.");
	}

	/**
	 * 策略标志位：在 Protostuff 默认值之上打开两项（按实例生效，不靠 JVM 系统属性）：
	 * <ul>
	 *   <li>{@code COLLECTION_SCHEMA_ON_REPEATED_FIELDS}：POJO 里的集合字段编码成嵌套消息而不是平铺的 repeated 字段，
	 *       空集合与 null 因此可以区分（默认模式下空集合字段解出来是 null）；</li>
	 *   <li>{@code PRESERVE_NULL_ELEMENTS}：POJO 集合字段里的 null 元素按位置保留，与顶层集合的行为一致。</li>
	 * </ul>
	 * 代价是与"默认标志的 Protostuff"线上格式不同；两端都是本序列化器，不受影响。
	 */
	static final int STRATEGY_FLAGS = io.protostuff.runtime.IdStrategy.DEFAULT_FLAGS
			| io.protostuff.runtime.IdStrategy.COLLECTION_SCHEMA_ON_REPEATED_FIELDS
			| io.protostuff.runtime.IdStrategy.PRESERVE_NULL_ELEMENTS;

	private final List<String> allowedPrefixes;
	private final AllowlistIdStrategy strategy;
	private final TypeSchemas schemas;

	public ProtobufSerializer() {
		this(List.of());
	}

	/** @param extraAllowedPrefixes 在默认白名单之外允许的包前缀（以 {@code .} 结尾表示包，否则按类名前缀匹配） */
	public ProtobufSerializer(Collection<String> extraAllowedPrefixes) {
		List<String> prefixes = new ArrayList<>(defaultAllowedPrefixes());
		for (String prefix : extraAllowedPrefixes) {
			String trimmed = prefix == null ? "" : prefix.trim();
			if (!trimmed.isEmpty() && !prefixes.contains(trimmed)) {
				prefixes.add(trimmed);
			}
		}
		this.allowedPrefixes = List.copyOf(prefixes);
		this.strategy = new AllowlistIdStrategy(STRATEGY_FLAGS, this::isAllowed,
				"allowed prefixes " + allowedPrefixes + "; add business packages via serializer option '" + OPTION_ALLOWED_PACKAGES + "'");
		// java.time 的标量编码注册成委托：POJO 字段、集合元素、多态位置上的这些值也按 ISO 文本编码（见 JavaTimeDelegates）
		for (io.protostuff.runtime.Delegate<?> delegate : JavaTimeDelegates.all()) {
			strategy.registerDelegate(delegate);
		}
		this.schemas = new TypeSchemas(strategy);
	}

	@Override
	public byte code() {
		return 2;
	}

	@Override
	public String name() {
		return "protobuf";
	}

	@Override
	public byte[] serialize(Object value, Type declaredType) {
		return schemas.root(declaredType).write(value);
	}

	@Override
	public Object deserialize(byte[] bytes, Type declaredType) {
		return schemas.root(declaredType).read(bytes);
	}

	/** 读取 {@value #OPTION_ALLOWED_PACKAGES}；没有该键时返回自身。 */
	@Override
	public Serializer withOptions(Map<String, String> options) {
		String value = options.get(OPTION_ALLOWED_PACKAGES);
		if (value == null || value.isBlank()) {
			return this;
		}
		return new ProtobufSerializer(List.of(value.split(",")));
	}

	/** 当前生效的白名单前缀（默认 + 配置追加）。 */
	public List<String> allowedPrefixes() {
		return allowedPrefixes;
	}

	boolean isAllowed(String className) {
		for (String prefix : allowedPrefixes) {
			if (className.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** 本实例私有的 IdStrategy（测试用：证明守卫与"不碰全局策略"）。 */
	AllowlistIdStrategy idStrategy() {
		return strategy;
	}

	TypeSchemas schemas() {
		return schemas;
	}
}
