package io.github.sisyfyzhang.steadyrpc.starter;

import io.github.sisyfyzhang.steadyrpc.core.config.RpcClientConfig;

/**
 * 在 starter 创建 {@code RpcClient} 之前程序化修改它的配置 builder。容器里所有此类型的 bean 都会生效，
 * 按 {@code @Order} / {@code Ordered} 顺序执行，且在 {@code rpc.client.*} 属性与 {@code ClientFilter} bean
 * 应用<b>之后</b>执行——后写者覆盖，所以 customizer 可以改写任何来自 properties 的值。
 *
 * <p>为什么需要它：properties 只能表达字符串与数字，有些配置天然是代码（按环境计算的超时、额外的序列化选项、
 * 只在某些 profile 下追加的 Filter）。与其为每一种需求再加一个属性，不如把 core 的 builder 直接交给应用——
 * 这和 Boot 自己的 {@code RestClientCustomizer}、{@code JacksonCustomizer} 是同一种扩展方式，
 * 而 starter 依然只有一条"properties → 不可变配置"的路径。
 *
 * <pre>{@code
 * @Bean
 * RpcClientCustomizer longerTimeoutInStaging(Environment env) {
 *     return builder -> builder.defaultTimeout(Duration.ofSeconds(30));
 * }
 * }</pre>
 */
@FunctionalInterface
public interface RpcClientCustomizer {

	void customize(RpcClientConfig.Builder<?> builder);
}
