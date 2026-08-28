package com.feynman.rpc.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.feynman.rpc.core.mediator.InitialMediator;
import com.feynman.rpc.core.server.NettyInitializer;

/**
 * 服务端显式引入：{@code @Import(RpcProviderConfiguration.class)} 装配服务端的
 * {@link NettyInitializer} 和 {@link InitialMediator}，不会被消费端的组件扫描误捡到。
 */
@Configuration
public class RpcProviderConfiguration {

	@Bean
	public NettyInitializer nettyInitializer() {
		return new NettyInitializer();
	}

	@Bean
	public InitialMediator initialMediator() {
		return new InitialMediator();
	}
}
