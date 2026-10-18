package com.feynman.rpc.examples.api;

/**
 * 业务异常示例：库存不足。放在 api 包里与接口一起发布——消费端 classpath 上有这个类，框架就会把服务端抛出的
 * 异常还原成同一类型（消息一致、远端堆栈挂在 cause 上），调用方按类型 catch，和本地调用一样。
 * 需要 {@code (String)} 构造器，框架用它重建异常。
 */
public class InsufficientStockException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public InsufficientStockException(String message) {
		super(message);
	}
}
