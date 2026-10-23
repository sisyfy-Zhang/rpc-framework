package io.github.sisyfyzhang.steadyrpc.examples.consumer;

import java.util.List;

import org.springframework.stereotype.Component;

import io.github.sisyfyzhang.steadyrpc.examples.api.InsufficientStockException;
import io.github.sisyfyzhang.steadyrpc.examples.api.InventoryService;
import io.github.sisyfyzhang.steadyrpc.examples.api.User;
import io.github.sisyfyzhang.steadyrpc.starter.RpcReference;

/** 下单路径上的远程库存调用：业务异常按类型 catch，与本地调用写法相同。 */
@Component
public class OrderGateway {

	@RpcReference(timeout = 3000)
	private InventoryService inventory;

	/** @return 下单成功返回剩余库存，库存不足返回 -1 */
	public int placeOrder(long skuId, int quantity, String orderNo) {
		try {
			return inventory.reserve(skuId, quantity, orderNo);
		} catch (InsufficientStockException e) {
			return -1;
		}
	}

	public InventoryService inventory() {
		return inventory;
	}

	public List<User> buyers(long skuId) {
		return inventory.buyers(skuId);
	}
}
