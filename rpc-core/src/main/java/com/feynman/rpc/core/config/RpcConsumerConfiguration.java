package com.feynman.rpc.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.feynman.rpc.core.proxy.InvokeProxy;

/**
 * 消费端显式引入：{@code @Import(RpcConsumerConfiguration.class)} 只装配客户端的
 * {@link InvokeProxy}，而不是用一个大而全的 {@code @ComponentScan("com.feynman.rpc")}
 * 把服务端的 bean 也一并扫进来。
 */
@Configuration
public class RpcConsumerConfiguration {

	@Bean
	public InvokeProxy invokeProxy() {
		return new InvokeProxy();
	}
}
