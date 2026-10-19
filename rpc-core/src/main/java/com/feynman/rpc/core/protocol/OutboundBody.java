package com.feynman.rpc.core.protocol;

import io.netty.buffer.ByteBuf;

/**
 * 还没写进缓冲区的出站 body：字节已经备好（请求参数 / 返回值已序列化，或错误信封的字符串已截断），由编码器在
 * <b>事件循环线程</b>上按 {@link #length()} 一次分配、调用 {@link #writeTo} 写入。客户端请求与服务端回包都用它。
 *
 * <p>为什么不让调用方线程直接把 body 写进 {@code channel.alloc()} 分配的缓冲区：调用方线程通常是业务线程或虚拟线程，
 * 它们没有 Netty 池化分配器的线程缓存，每次分配 / 扩容 / 释放都要竞争 arena 锁；JDK 21 上
 * {@code PoolArena.reallocate} 还在 {@code synchronized (buf)} 内取 arena 锁，虚拟线程在这里会钉住载体线程，
 * 万级虚拟线程时载体全部钉死、锁的持有者却等不到载体，整个客户端卡死（见 docs/pitfalls.md 第 32 条）。
 * 服务端同理：业务执行器可以是虚拟线程（{@code ExecutorConfig.virtualThreads}），回包也不在业务线程上分配。
 * 另外调用方 / 业务线程上已分配、尚未交给 Netty 的缓冲区，只要中途抛出 Error（如直接内存耗尽）就会泄漏。
 * 只持有 byte[] 与字符串，没有引用计数，丢弃即回收。
 */
public interface OutboundBody {

	/** {@link #writeTo} 将写入的确切字节数。 */
	int length();

	/** 恰好写入 {@link #length()} 个字节。 */
	void writeTo(ByteBuf out);
}
