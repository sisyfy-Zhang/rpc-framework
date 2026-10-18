package com.feynman.rpc.core.config;

import java.time.Duration;

/**
 * 引用单个远程服务时的可选项。{@code timeout} 为 null 表示沿用客户端默认超时。
 *
 * <p>{@code retries} 默认 0：即使客户端配置了 failover 这类会重试的 Cluster，没有显式声明可重试的
 * 引用也不会被重试——非幂等调用被意外执行两次的代价远高于少一次重试。
 *
 * <p>{@code inheritDeadline} 默认 true：在服务端业务方法里（或 {@code RpcContext.withDeadline} 作用域内）发起的调用，
 * 超时取 {@code timeout} 与上游剩余预算的较小者。上游已经放弃的请求，下游继续按自己的长超时等待只是浪费资源
 * （gRPC 的 deadline 传播同理）。只有确实要"脱离上游生命周期"的调用（例如上游超时后仍要完成的补偿写）才关掉它。
 */
public record ReferenceOptions(String version, String group, Duration timeout, int retries, boolean inheritDeadline) {

	public ReferenceOptions {
		if (retries < 0) {
			throw new IllegalArgumentException("retries must be >= 0: " + retries);
		}
		if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
			throw new IllegalArgumentException("timeout must be positive: " + timeout);
		}
	}

	public static ReferenceOptions defaults() {
		return new ReferenceOptions(null, null, null, 0, true);
	}

	public ReferenceOptions version(String version) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline);
	}

	public ReferenceOptions group(String group) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline);
	}

	public ReferenceOptions timeout(Duration timeout) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline);
	}

	public ReferenceOptions retries(int retries) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline);
	}

	public ReferenceOptions inheritDeadline(boolean inheritDeadline) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline);
	}
}
