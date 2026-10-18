package com.feynman.rpc.examples.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.feynman.rpc.examples.api.User;

/** 启动后发起一次示例调用；{@code examples.consumer.demo-call=false} 可关闭（集成测试自己驱动调用）。 */
@Component
@ConditionalOnProperty(prefix = "examples.consumer", name = "demo-call", havingValue = "true", matchIfMissing = true)
public class DemoRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

	private final UserGateway gateway;

	public DemoRunner(UserGateway gateway) {
		this.gateway = gateway;
	}

	@Override
	public void run(ApplicationArguments args) {
		User saved = gateway.register(1, "zzz");
		log.info("Remote saveUser returned {}", saved);
	}
}
