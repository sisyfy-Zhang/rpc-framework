package com.feynman.rpc.bench;

import java.util.Map;

/**
 * 服务端元数据与负载的公开入口：对比对象（gRPC、Dubbo 等）用它返回与 bench server 完全相同的字段、发送完全相同的字节，
 * 报告里的 {@code server_environment} 与 {@code rounds[].gc.server} 因此对各个 transport 同一口径。
 */
public final class ServerProbes {

	private ServerProbes() {
	}

	/** 本进程的运行环境（与压测端 {@code environment} 同一套字段，值压平成字符串）；服务端应在启动时采集一次、之后直接返回。 */
	public static Map<String, String> environment() {
		return EnvironmentProbe.collectAsStrings();
	}

	/** 本进程此刻的累计 GC 次数 / 停顿与堆用量（值压平成字符串），压测端在统计窗口起止各取一次做差。 */
	public static Map<String, String> gcStats() {
		return GcSampler.snapshot().toStrings();
	}

	/**
	 * 服务端统计端点（{@code BenchService.stats()}、gRPC 的 {@code Stats}、{@code GET /stats}）返回的全部字段：{@link #gcStats()}
	 * 加上本进程的读写系统调用与上下文切换累计值（{@link IoSampler}，键以 {@code io.} 开头；非 Linux 时没有这些键）。
	 * 压测端在统计窗口起止各取一次做差，分别进 {@code rounds[].gc.server} 与 {@code rounds[].io.server}。
	 */
	public static Map<String, String> stats() {
		Map<String, String> out = new java.util.LinkedHashMap<>(gcStats());
		IoSampler.Snapshot io = IoSampler.snapshot();
		if (io != null) {
			out.putAll(io.toStrings());
		}
		return out;
	}

	/** {@code size} 字节的压测负载：固定种子随机内容，与 rpc / http 卡发送的字节完全相同。 */
	public static byte[] payload(int size) {
		return Payloads.bytes(size);
	}
}
