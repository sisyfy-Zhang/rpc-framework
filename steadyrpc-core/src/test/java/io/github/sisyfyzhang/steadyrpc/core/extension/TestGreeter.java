package io.github.sisyfyzhang.steadyrpc.core.extension;

@SPI("hello")
public interface TestGreeter {
	String greet();
}
