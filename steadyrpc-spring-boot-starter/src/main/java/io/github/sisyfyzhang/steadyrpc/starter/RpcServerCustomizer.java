package io.github.sisyfyzhang.steadyrpc.starter;

import io.github.sisyfyzhang.steadyrpc.core.config.RpcServerConfig;

/**
 * 在 starter 创建 {@code RpcServer} 之前程序化修改它的配置 builder。语义与 {@link RpcClientCustomizer} 相同：
 * 所有此类型的 bean 按 {@code @Order} 顺序、在 {@code rpc.server.*} 属性与 {@code ServerFilter} bean 应用之后执行。
 *
 * <pre>{@code
 * @Bean
 * RpcServerCustomizer virtualThreads() {
 *     return builder -> builder.executor(ExecutorConfig.virtualThreads(2000));
 * }
 * }</pre>
 */
@FunctionalInterface
public interface RpcServerCustomizer {

	void customize(RpcServerConfig.Builder<?> builder);
}
