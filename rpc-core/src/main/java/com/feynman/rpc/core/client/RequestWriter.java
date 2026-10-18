package com.feynman.rpc.core.client;

import com.feynman.rpc.core.serialize.Serializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * 在拿到连接、确定了这条连接协商出的序列化器之后，编码请求 body。为什么延迟到这一步：序列化方式是按连接协商的，
 * 选中节点、拿到连接之前不知道该用哪个序列化器。返回的缓冲区所有权交给调用方（随请求帧写出后由编码器释放）；
 * 实现抛异常时必须自行释放已分配的缓冲区。
 */
@FunctionalInterface
public interface RequestWriter {

	ByteBuf write(ByteBufAllocator alloc, Serializer serializer);
}
