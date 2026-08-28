package com.feynman.rpc.core.serialize;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.feynman.rpc.core.extension.ExtensionLoader;

/** 按协议层的 {@link Serializer#code()} 字节查找对应的 {@link Serializer}。 */
public final class Serializers {

	private static final Map<Byte, Serializer> BY_CODE = new ConcurrentHashMap<>();

	private Serializers() {
	}

	public static Serializer byCode(byte code) {
		return BY_CODE.computeIfAbsent(code, c -> {
			ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
			for (String name : loader.getSupportedExtensions()) {
				Serializer candidate = loader.getExtension(name);
				if (candidate.code() == c) {
					return candidate;
				}
			}
			throw new IllegalStateException("No Serializer registered for wire code " + c);
		});
	}
}
