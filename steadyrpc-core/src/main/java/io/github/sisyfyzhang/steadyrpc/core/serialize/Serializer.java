package io.github.sisyfyzhang.steadyrpc.core.serialize;

import java.lang.reflect.Type;
import java.util.Map;

import io.github.sisyfyzhang.steadyrpc.core.extension.SPI;

/**
 * 序列化扩展点。各实现分别在独立模块中（steadyrpc-serialize-fastjson2、steadyrpc-serialize-protobuf），
 * 调用方只需引入自己实际用到的编解码器。
 *
 * <p>{@link #code()} 是写入帧头、在连接协商时交换的稳定单字节标识——一旦某个已发布的实现分配了这个值就不能再改。
 * {@link #name()} 是 SPI 名字（{@code META-INF/rpc/...} 里的 key）。
 *
 * <p><b>为什么带声明类型</b>：只给运行时 Class 时，{@code List<User>} 只剩 {@code ArrayList}，元素还原成 Map / JSONObject；
 * 而 Protostuff 对 JDK 集合类调 RuntimeSchema 会静默损坏数据并污染全局状态（D-005）。框架总是传入方法签名上的
 * 泛型类型：参数用 {@code Method.getGenericParameterTypes()}，返回值用 {@code getGenericReturnType()}
 * （{@code CompletableFuture<T>} 取 T）。
 *
 * <p>约定（框架保证）：
 * <ul>
 *   <li>{@link #serialize} 不会收到 null——null 由信封的 present 标志表达，序列化器不需要区分；</li>
 *   <li>{@link #deserialize} 收到 0 字节时应返回"空对象"（POJO 的无参实例、空集合），而不是 null；</li>
 *   <li>信封与 attachments 不经过序列化器，实现只处理业务参数与返回值。</li>
 * </ul>
 * 实现必须线程安全；每个 RpcClient / RpcServer 各自持有一组实例（见 {@link Serializers}）。
 *
 * <p><b>缺省名 {@code protobuf}</b>（steadyrpc-serialize-protobuf，基于 Protostuff）：不需要 IDL、二进制紧凑，且字节流里
 * 不带类名——目标类型由方法签名决定，不存在"按数据里的类名实例化"（DECISIONS D-001）。未配置序列化方式时 classpath 上
 * 必须有该模块；只带 fastjson2 的应用要显式配置 {@code fastjson2}。缺省名只决定本端首选，协商规则不变：
 * 首选排第一，其余按 SPI 声明顺序。
 */
@SPI("protobuf")
public interface Serializer {

	byte code();

	String name();

	byte[] serialize(Object value, Type declaredType);

	Object deserialize(byte[] bytes, Type declaredType);

	/**
	 * 按配置返回一个（可能是新的）实例。键由各实现自己定义并以 SPI 名为前缀，例如 Protostuff 的
	 * {@code protobuf.allowed-packages}；不认识的键忽略（同一份配置会交给所有实现）。默认无配置项，返回自身。
	 *
	 * <p>为什么返回新实例而不是就地修改：同一个 JVM 里不同的 RpcClient / RpcServer 可以有不同配置，
	 * 实例按客户端 / 服务端各持一份，不共享可变状态。
	 */
	default Serializer withOptions(Map<String, String> options) {
		return this;
	}
}
