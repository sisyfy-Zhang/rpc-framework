package io.github.sisyfyzhang.steadyrpc.core.client;

import io.github.sisyfyzhang.steadyrpc.core.protocol.OutboundBody;
import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializer;

/**
 * 在拿到连接、确定了这条连接协商出的序列化器之后，备好请求 body。为什么延迟到这一步：序列化方式是按连接协商的，
 * 选中节点、拿到连接之前不知道该用哪个序列化器。
 *
 * <p>只做序列化、返回备好的字节（{@link OutboundBody}），<b>不分配 ByteBuf</b>：它运行在调用方线程（常是虚拟线程）上，
 * 缓冲区由编码器在事件循环上分配（理由见 {@link OutboundBody}）。实现抛出的任何异常（包括 Error）都让本次请求以
 * {@link io.github.sisyfyzhang.steadyrpc.core.RpcSerializationException} 失败，不会挂到超时。
 */
@FunctionalInterface
public interface RequestWriter {

	OutboundBody write(Serializer serializer);
}
