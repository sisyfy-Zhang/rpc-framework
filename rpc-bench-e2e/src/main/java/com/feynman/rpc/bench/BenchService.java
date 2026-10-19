package com.feynman.rpc.bench;

import java.util.Map;

/**
 * 压测目标服务。两个方法对应口径卡 {@code target.service} 的 {@code BenchService#echo} 与 {@code BenchService#echoOrder}，
 * 实现原样返回入参：测的是框架（编解码、序列化、网络、调度）的开销，不是业务逻辑。
 */
public interface BenchService {

	/** payload.kind = bytes：size 字节的固定种子随机内容。 */
	byte[] echo(byte[] payload);

	/** payload.kind = order：一个 8 字段的 POJO（含 List 与 java.time），覆盖序列化器的对象图路径。 */
	Order echoOrder(Order order);

	/**
	 * 服务端进程的运行环境（与压测端 {@code environment} 同一套字段，值压平成字符串，含 jvm_args、cpus_allowed、pid、
	 * hostname）。服务端启动时采集一次，这里直接返回：压测端在预热前调用一次，写进报告的 {@code server_environment}。
	 */
	Map<String, String> environment();

	/**
	 * 服务端进程此刻的累计 GC 次数 / 停顿时间（按收集器）与堆用量，值压平成字符串（{@link GcSampler.Snapshot#toStrings()}）。
	 * 压测端在每轮统计窗口起止各调用一次，差值写进 {@code rounds[].gc.server}。
	 */
	Map<String, String> stats();
}
