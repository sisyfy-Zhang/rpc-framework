package com.feynman.rpc.core.loadbalance;

import java.util.List;

import com.feynman.rpc.core.extension.SPI;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 选出下一次要调用的提供者节点；不关心也不知道连接本身——下层的连接池负责把选出的地址解析成
 * 实际 channel。刻意只保留两种实现（一个简单基线、一个真正打磨过的），不做 Dubbo 那种五六种策略的大杂烩。
 *
 * <p>负载信息（{@link LoadStats}）作为参数传入而不是由实现自己去全局读取：统计归 RpcClient 实例所有，
 * 实现保持可替换、可单测。实现可以有内部状态（如轮询游标），因此每个 RpcClient 各自 new 一份，不共享。
 */
@SPI("roundrobin")
public interface LoadBalance {

	/**
	 * @param candidates 当前可选的提供者（权重见 {@link ServiceInstance#weight()}）
	 * @param stats      所属客户端的在途请求统计
	 * @return 选中的节点；{@code candidates} 为空时返回 {@code null}
	 */
	ServiceInstance select(List<ServiceInstance> candidates, LoadStats stats);
}
