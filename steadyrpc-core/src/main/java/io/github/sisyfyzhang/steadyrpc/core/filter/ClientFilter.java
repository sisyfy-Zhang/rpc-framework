package io.github.sisyfyzhang.steadyrpc.core.filter;

/**
 * 标记：该 Filter 用于客户端链。行为上与 {@link Filter} 相同，存在的意义是让 starter 能按类型自动收集
 * Filter bean 并放到正确的一端；一个类可以同时实现 {@code ClientFilter} 与 {@link ServerFilter}。
 */
public interface ClientFilter extends Filter {
}
