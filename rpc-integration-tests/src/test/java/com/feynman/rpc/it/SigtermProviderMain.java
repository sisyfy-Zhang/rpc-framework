package com.feynman.rpc.it;

import org.springframework.context.ConfigurableApplicationContext;

import com.feynman.rpc.examples.provider.ProviderApplication;
import com.feynman.rpc.starter.RpcService;

/**
 * {@link SigtermShutdownTest} 在独立 JVM 里启动的 provider：rpc-examples 的 ProviderApplication（同一套配置与 starter），
 * 额外暴露一个慢服务。慢调用开始时向 stdout 打印 {@value #SLOW_STARTED} 标记，测试据此确认"在途请求已进入服务端"
 * 再发 SIGTERM（跨进程没有别的同步手段）。
 */
public final class SigtermProviderMain {

	static final String SLOW_STARTED = "SIGTERM-TEST: slow call started";
	static final String READY = "SIGTERM-TEST: provider ready";

	@RpcService(interfaceClass = GracefulShutdownTest.SlowEcho.class)
	public static class AnnouncingSlowEcho extends GracefulShutdownTest.SlowEchoImpl {
		@Override
		public String slow(int millis) {
			System.out.println(SLOW_STARTED);
			System.out.flush();
			return super.slow(millis);
		}
	}

	private SigtermProviderMain() {
	}

	public static void main(String[] args) {
		ConfigurableApplicationContext context = ProviderApplication.builder().sources(AnnouncingSlowEcho.class).run(args);
		System.out.println(READY + " (context active=" + context.isActive() + ")");
		System.out.flush();
		// main 返回；进程由 Netty 的非守护线程保活，直到 SIGTERM 触发 Spring 的关闭钩子
	}
}
