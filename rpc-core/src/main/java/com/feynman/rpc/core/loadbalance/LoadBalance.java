package com.feynman.rpc.core.loadbalance;

import java.util.Map;

import com.feynman.rpc.core.extension.SPI;

/**
 * 选出下一次要调用的服务地址；不关心也不知道连接本身——下层的连接池（见 ChannelManager/
 * ConnectionPool）负责把选出的地址解析成实际 channel。刻意只保留两种实现（一个简单基线、
 * 一个真正打磨过的），不做 Dubbo 那种五六种策略的大杂烩。
 */
@SPI("roundrobin")
public interface LoadBalance {

	/**
	 * @param addressWeights 当前所有存活服务地址及其注册权重（>=1）
	 * @return 选中的地址，{@code addressWeights} 为空时返回 {@code null}
	 */
	String select(Map<String, Integer> addressWeights);
}
