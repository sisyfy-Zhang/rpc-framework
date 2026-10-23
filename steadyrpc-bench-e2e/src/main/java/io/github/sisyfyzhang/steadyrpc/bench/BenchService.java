package io.github.sisyfyzhang.steadyrpc.bench;

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
	 * 服务端进程此刻的累计 GC 次数 / 停顿时间（按收集器）与堆用量、读写系统调用与上下文切换次数，值压平成字符串
	 * （{@link ServerProbes#stats()}）。压测端在每轮统计窗口起止各调用一次，差值写进 {@code rounds[].gc.server} / {@code rounds[].io.server}。
	 */
	Map<String, String> stats();

	/**
	 * 服务端此刻的轻量累计计数（不读 /proc，可以每秒调用）：执行器过载 / 停机拒绝、出站硬上限拒绝、在途与排队数、排队等待的累计直方图
	 * （键见 {@link ServerCounters}）。故障注入实验的时间线每秒对每个提供者取一次，差值进 {@code rounds[].timeline}。
	 * 不是 rpc bench server 的实现（对比对象）返回空表。
	 */
	default Map<String, String> counters() {
		return Map.of();
	}
}
