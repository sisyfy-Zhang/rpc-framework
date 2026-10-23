package io.github.sisyfyzhang.steadyrpc.core.testutil;

/**
 * 交给 {@link SimpleTestSerializer} 序列化时抛出 {@link #error()}：模拟序列化器在返回值上抛 Error
 * （对象图过深的 StackOverflowError、序列化中直接内存耗尽等），用来测服务端"序列化结果时抛 Error"的回包路径。
 */
public record SerializationBomb(Error error) {
}
