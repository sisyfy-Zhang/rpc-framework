package com.feynman.rpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;

/** 原样返回入参，不做任何业务计算；{@link #environment()} 返回构造时采集好的服务端环境。 */
public final class BenchServiceImpl implements BenchService {

	private final Map<String, String> environment;

	public BenchServiceImpl(Map<String, String> environment) {
		this.environment = new LinkedHashMap<>(environment);
	}

	@Override
	public byte[] echo(byte[] payload) {
		return payload;
	}

	@Override
	public Order echoOrder(Order order) {
		return order;
	}

	@Override
	public Map<String, String> environment() {
		return new LinkedHashMap<>(environment);
	}
}
