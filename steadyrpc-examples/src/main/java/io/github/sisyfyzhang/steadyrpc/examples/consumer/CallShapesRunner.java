package io.github.sisyfyzhang.steadyrpc.examples.consumer;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.sisyfyzhang.steadyrpc.core.RpcContext;
import io.github.sisyfyzhang.steadyrpc.examples.api.InsufficientStockException;
import io.github.sisyfyzhang.steadyrpc.examples.api.InventoryService;
import io.github.sisyfyzhang.steadyrpc.examples.api.User;

/** 启动后再演示三种调用形态：业务异常跨进程、异步、attachments（同步调用见 {@link DemoRunner}）。 */
@Component
@ConditionalOnProperty(prefix = "examples.consumer", name = "demo-call", havingValue = "true", matchIfMissing = true)
public class CallShapesRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(CallShapesRunner.class);

	private final OrderGateway orders;

	public CallShapesRunner(OrderGateway orders) {
		this.orders = orders;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		demo(3001L).forEach(line -> log.info("{}", line));
	}

	/** 对同一个 SKU（初始 10 件）依次调用，返回每一步的结果。 */
	public List<String> demo(long skuId) throws Exception {
		InventoryService inventory = orders.inventory();
		int left = inventory.reserve(skuId, 3, "order-1");
		String rejected;
		try {
			inventory.reserve(skuId, 100, "order-2");
			rejected = "unexpected success";
		} catch (InsufficientStockException e) {                      // 业务异常按类型还原；远端堆栈在 e.getCause()
			rejected = "InsufficientStockException: " + e.getMessage();
		}
		List<User> buyers = inventory.buyersAsync(skuId).get(5, TimeUnit.SECONDS);   // 异步：返回 CompletableFuture
		RpcContext.setAttachment("tenant", "acme");                  // 只随紧接着的下一次调用发出，发出即清空
		int tagged = inventory.buyers(skuId).size();                 // provider 的 TenantFilter 读到 tenant=acme
		return List.of("left=" + left, rejected, "async buyers=" + buyers, "buyers(tenant=acme)=" + tagged);
	}
}
