package com.feynman.rpc.core.config;

/**
 * 暴露单个服务时的可选项。{@code weight} 为 0 表示沿用服务端级别的默认权重——同一进程里的
 * 不同服务通常同权，只有个别服务需要单独调。
 */
public record ServiceOptions(String version, String group, int weight) {

	public ServiceOptions {
		if (weight < 0) {
			throw new IllegalArgumentException("weight must be >= 0: " + weight);
		}
	}

	public static ServiceOptions defaults() {
		return new ServiceOptions(null, null, 0);
	}

	public ServiceOptions version(String version) {
		return new ServiceOptions(version, group, weight);
	}

	public ServiceOptions group(String group) {
		return new ServiceOptions(version, group, weight);
	}

	public ServiceOptions weight(int weight) {
		return new ServiceOptions(version, group, weight);
	}
}
