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
 *
 * <p>{@code awaitProviders} 默认 null（不等待）：设置后 {@code RpcClient.reference()} 阻塞到该服务的提供者视图非空，
 * 最多等这么久，超时抛 {@code RpcException}。为什么需要它：registered ≠ discovered——提供者注册完成只表示节点已写入
 * 注册中心，消费者若在那之前就已订阅，要靠注册中心的推送才能看到它，推送可能晚于紧随其后的第一次调用。
 * 不设置时 reference 照旧立即返回，没有提供者的调用以 "No available provider" 快速失败。
 * 传 {@link Duration#ZERO} 等同于不等待（规范化为 null）。
 */
public record ReferenceOptions(String version, String group, Duration timeout, int retries, boolean inheritDeadline,
		Duration awaitProviders) {

	public ReferenceOptions {
		if (retries < 0) {
			throw new IllegalArgumentException("retries must be >= 0: " + retries);
		}
		if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
			throw new IllegalArgumentException("timeout must be positive: " + timeout);
		}
		if (awaitProviders != null && awaitProviders.isNegative()) {
			throw new IllegalArgumentException("awaitProviders must not be negative: " + awaitProviders);
		}
		if (awaitProviders != null && awaitProviders.isZero()) {
			awaitProviders = null;
		}
	}

	/** 不等待提供者（{@code awaitProviders = null}）的构造器，保持引入 awaitProviders 之前的源码兼容。 */
	public ReferenceOptions(String version, String group, Duration timeout, int retries, boolean inheritDeadline) {
		this(version, group, timeout, retries, inheritDeadline, null);
	}

	public static ReferenceOptions defaults() {
		return new ReferenceOptions(null, null, null, 0, true, null);
	}

	public ReferenceOptions version(String version) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline, awaitProviders);
	}

	public ReferenceOptions group(String group) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline, awaitProviders);
	}

	public ReferenceOptions timeout(Duration timeout) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline, awaitProviders);
	}

	public ReferenceOptions retries(int retries) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline, awaitProviders);
	}

	public ReferenceOptions inheritDeadline(boolean inheritDeadline) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline, awaitProviders);
	}

	/** {@code reference()} 等待提供者出现的上限；null 或 {@link Duration#ZERO} 表示不等待（默认）。见类注释。 */
	public ReferenceOptions awaitProviders(Duration awaitProviders) {
		return new ReferenceOptions(version, group, timeout, retries, inheritDeadline, awaitProviders);
	}
}
