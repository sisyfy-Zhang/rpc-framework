package com.feynman.rpc.it.support;

import java.util.concurrent.TimeUnit;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.ServiceKey;

import static org.awaitility.Awaitility.await;

/**
 * 集成测试的服务发现等待工具。
 */
public final class Discovery {

	private Discovery() {
	}

	/** 同 {@link #awaitProviders(String, RpcClient, Class)}，等待别名里的客户端名记为 "client"。 */
	public static void awaitProviders(RpcClient client, Class<?> itf) {
		awaitProviders("client", client, itf);
	}

	/**
	 * 等到 {@code client} 的提供者视图里出现 {@code itf}（缺省 version / group，与 {@code ReferenceOptions.defaults()} 一致）的提供者，
	 * 最多 10 秒，等待别名为 "{clientName} sees {itf}"。
	 * registered ≠ discovered：{@code RpcServer.registered()} 只表示节点已写入注册中心；消费者若在提供者注册之前就已订阅，
	 * 要靠 watch 推送才能看到它，推送可能晚于紧随其后的调用。
	 */
	public static void awaitProviders(String clientName, RpcClient client, Class<?> itf) {
		ServiceKey key = ServiceKey.of(itf);
		await(clientName + " sees " + itf.getSimpleName()).atMost(10, TimeUnit.SECONDS)
				.until(() -> !client.providers(key).isEmpty());
	}
}
