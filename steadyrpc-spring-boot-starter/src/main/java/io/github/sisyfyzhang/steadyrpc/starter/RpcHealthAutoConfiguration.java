package io.github.sisyfyzhang.steadyrpc.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

import io.github.sisyfyzhang.steadyrpc.core.RpcServer;

/**
 * 可选的健康指示器装配：只有 classpath 上有 Actuator（Boot 4 中 {@code HealthIndicator} 位于 spring-boot-health，
 * 随 spring-boot-starter-actuator 引入）时才生效。starter 对 Actuator 是 optional 依赖，不会把它强加给使用方。
 *
 * <p>独立成一个自动配置类而不是 {@link RpcAutoConfiguration} 的内部方法：条件在加载类之前用字节码元数据判断，
 * 没有 Actuator 的应用永远不会加载引用了 {@code HealthIndicator} 的类。bean 名 {@code rpcHealthIndicator}
 * 决定了它在 health 端点里的组件名 {@code rpc}，也对应开关 {@code management.health.rpc.enabled}。
 */
@AutoConfiguration(after = RpcAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
public class RpcHealthAutoConfiguration {

	@Bean
	@ConditionalOnBean(RpcServer.class)
	@ConditionalOnMissingBean(name = "rpcHealthIndicator")
	@ConditionalOnEnabledHealthIndicator("rpc")
	public RpcServerHealthIndicator rpcHealthIndicator(RpcServer rpcServer) {
		return new RpcServerHealthIndicator(rpcServer);
	}
}
