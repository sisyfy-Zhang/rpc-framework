package com.feynman.rpc.core.serialize;

import com.feynman.rpc.core.extension.SPI;

/**
 * 序列化扩展点。各实现分别在独立模块中（rpc-serialize-fastjson2、rpc-serialize-protobuf），
 * 调用方只需引入自己实际用到的编解码器。
 *
 * <p>{@link #code()} 是写入协议头（见 {@code RpcMessage.serializeType}）、在连接协商时交换的
 * 稳定单字节标识——一旦某个已发布的实现分配了这个值就不能再改。{@link #name()} 是可读的 SPI
 * 名字（对应 {@code META-INF/rpc/...} 配置文件里的 key），供协商双方按名字声明/比较支持的能力。
 */
@SPI("fastjson2")
public interface Serializer {

	byte code();

	String name();

	byte[] serialize(Object obj);

	<T> T deserialize(byte[] bytes, Class<T> type);
}
