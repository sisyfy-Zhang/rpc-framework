package io.github.sisyfyzhang.steadyrpc.examples.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import io.github.sisyfyzhang.steadyrpc.examples.api.InsufficientStockException;
import io.github.sisyfyzhang.steadyrpc.examples.api.InventoryService;
import io.github.sisyfyzhang.steadyrpc.examples.api.User;
import io.github.sisyfyzhang.steadyrpc.starter.RpcService;

/** 内存版库存：每个 SKU 初始 10 件。 */
@RpcService
public class InventoryServiceImpl implements InventoryService {

	private static final int INITIAL_STOCK = 10;

	private final Map<Long, Integer> stock = new ConcurrentHashMap<>();
	private final Map<Long, List<User>> buyers = new ConcurrentHashMap<>();

	@Override
	public synchronized int reserve(long skuId, Integer quantity, String orderNo) {
		int left = stock.getOrDefault(skuId, INITIAL_STOCK);
		if (quantity == null || quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive: " + quantity);
		}
		if (left < quantity) {
			throw new InsufficientStockException("sku " + skuId + " has " + left + " left, order " + orderNo + " wants " + quantity);
		}
		stock.put(skuId, left - quantity);
		List<User> list = buyers.computeIfAbsent(skuId, k -> new ArrayList<>());
		list.add(new User(list.size() + 1, orderNo));
		return left - quantity;
	}

	@Override
	public synchronized List<User> buyers(long skuId) {
		return new ArrayList<>(buyers.getOrDefault(skuId, List.of()));
	}

	@Override
	public CompletableFuture<List<User>> buyersAsync(long skuId) {
		return CompletableFuture.completedFuture(buyers(skuId));
	}
}
