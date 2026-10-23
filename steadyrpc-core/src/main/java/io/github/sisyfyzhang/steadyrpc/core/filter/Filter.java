package io.github.sisyfyzhang.steadyrpc.core.filter;

import java.util.concurrent.CompletableFuture;

/**
 * 客户端与服务端共用的拦截器扩展点（对齐 Dubbo Filter / gRPC Interceptor）。deadline、过载保护、
 * 指标、trace 都实现为 Filter，而不是写死在调用路径里——线 B 的横切需求可以不改框架就接入。
 *
 * <p>为什么返回 {@link CompletableFuture}：调用路径是异步优先的，同步调用只是在 future 上等待。
 * Filter 若是同步签名，就只能阻塞等下游结果，异步调用会被拦截器悄悄变成同步。
 *
 * <p>实现可以在调用 {@code next} 之前改写 {@link Invocation#setAttachment attachments}、
 * 直接返回结果而不调用 {@code next}（拒绝 / 短路），或在返回的 future 上追加处理。
 *
 * <p>顺序：链按 {@link #order()} 升序稳定排序，order 小的在外层先执行；同 order 保持注册顺序。内置 Filter 用负值
 * （见 {@link DeadlineClientFilter#ORDER}、{@link DeadlineServerFilter#ORDER}），用户 Filter 默认 0，因此默认排在
 * 内置之后、可以看到内置 Filter 写入的 attachments；需要插到内置之前时给更小的 order。
 */
@FunctionalInterface
public interface Filter {

	CompletableFuture<Result> invoke(Invoker next, Invocation invocation);

	/** 越小越靠外（越先执行）。默认 0。 */
	default int order() {
		return 0;
	}
}
