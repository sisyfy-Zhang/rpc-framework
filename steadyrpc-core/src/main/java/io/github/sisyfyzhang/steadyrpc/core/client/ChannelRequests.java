package io.github.sisyfyzhang.steadyrpc.core.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * 每条连接上已写出、尚未收到响应的 requestId。连接断开时据此让这些请求<b>立即</b>失败，而不是挂到各自的超时：
 * 服务端停机超时后关连接、对端崩溃、网络中断时，调用方应该马上知道"响应不会来了"。
 *
 * <p>这类失败属于传输失败（D-009 的"连接重置"）：future 异常完成，Cluster 按引用的 retries 决定是否换节点重试。
 * 请求可能已在服务端执行，所以只有声明了可重试（幂等）的引用才会被重试——这正是 retries 默认 0 的原因。
 */
final class ChannelRequests {

	static final AttributeKey<Set<Long>> KEY = AttributeKey.valueOf("rpc-in-flight-request-ids");

	private ChannelRequests() {
	}

	static Set<Long> of(Channel channel) {
		Set<Long> ids = channel.attr(KEY).get();
		if (ids == null) {
			Set<Long> created = ConcurrentHashMap.newKeySet();
			ids = channel.attr(KEY).setIfAbsent(created);
			if (ids == null) {
				ids = created;
			}
		}
		return ids;
	}
}
