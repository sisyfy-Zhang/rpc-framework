package io.github.sisyfyzhang.steadyrpc.core.serialize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.sisyfyzhang.steadyrpc.core.extension.ExtensionLoader;

/**
 * 一个 RpcClient / RpcServer 持有的一组序列化器：按帧头编码字节查找、按优先级列出供协商。
 *
 * <p>为什么是实例而不是全局表：序列化器现在可以带配置（例如 Protostuff 的多态白名单），同一 JVM 里的两个服务端
 * 可以配置不同；实例随所属客户端 / 服务端创建，不再有进程级缓存。每个实现用 {@code newExtension} 新建再
 * {@link Serializer#withOptions} 配置。
 */
public final class Serializers {

	private final Serializer preferred;
	private final Map<Byte, Serializer> byCode;
	private final List<Byte> codesByPriority;

	private Serializers(Serializer preferred, Map<Byte, Serializer> byCode, List<Byte> codesByPriority) {
		this.preferred = preferred;
		this.byCode = byCode;
		this.codesByPriority = codesByPriority;
	}

	/**
	 * 加载 classpath 上声明的全部 Serializer 实现，{@code preferredName} 排第一。
	 *
	 * @throws IllegalStateException {@code preferredName} 不存在
	 */
	public static Serializers load(String preferredName, Map<String, String> options) {
		ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
		Map<String, String> opts = options == null ? Map.of() : options;
		Serializer preferred = loader.newExtension(preferredName).withOptions(opts);
		Map<Byte, Serializer> byCode = new LinkedHashMap<>();
		List<Byte> codes = new ArrayList<>();
		byCode.put(preferred.code(), preferred);
		codes.add(preferred.code());
		for (String name : loader.getSupportedExtensions()) {
			if (name.equals(preferredName)) {
				continue;
			}
			Serializer serializer = loader.newExtension(name).withOptions(opts);
			if (byCode.putIfAbsent(serializer.code(), serializer) == null) {
				codes.add(serializer.code());
			}
		}
		return new Serializers(preferred, Collections.unmodifiableMap(byCode), List.copyOf(codes));
	}

	public Serializer preferred() {
		return preferred;
	}

	/** 本端是否支持该编码；不支持返回 null。 */
	public Serializer find(byte code) {
		return byCode.get(code);
	}

	public Serializer byCode(byte code) {
		Serializer serializer = byCode.get(code);
		if (serializer == null) {
			throw new IllegalStateException("No Serializer registered for wire code " + code);
		}
		return serializer;
	}

	/** 本端支持的编码，首选实现第一，其余按 SPI 声明顺序。每次返回新数组。 */
	public byte[] codesByPriority() {
		byte[] result = new byte[codesByPriority.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = codesByPriority.get(i);
		}
		return result;
	}
}
