package com.feynman.rpc.core.client;

import io.netty.util.AttributeKey;

/** 每条连接各自缓存与对端协商出的序列化方式编码。 */
final class NegotiatedSerializer {

	static final AttributeKey<Byte> KEY = AttributeKey.valueOf("rpc-negotiated-serialize-type");

	private NegotiatedSerializer() {
	}
}
