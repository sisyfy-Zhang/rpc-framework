package com.feynman.rpc.examples.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 库存服务（下单 → 预占库存的调用形态）：多参数、基本类型与包装类型混用、泛型返回、异步方法、业务异常。
 * 服务端按"方法名 + 参数类型签名"分发、按声明的泛型类型逐个反序列化参数，所以这些写法与本地接口没有区别。
 */
public interface InventoryService {

	/**
	 * 预占库存。
	 *
	 * @return 预占后的剩余库存
	 * @throws InsufficientStockException 库存不足（业务失败，不重试）
	 */
	int reserve(long skuId, Integer quantity, String orderNo);

	/** 某 SKU 的购买者（按预占顺序）。 */
	List<User> buyers(long skuId);

	/** 同上，异步形态：返回值的声明类型是 {@code CompletableFuture<List<User>>}，框架按 T = List&lt;User&gt; 反序列化。 */
	CompletableFuture<List<User>> buyersAsync(long skuId);
}
