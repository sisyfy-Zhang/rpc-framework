package io.github.sisyfyzhang.steadyrpc.core.extension;

public class HelloGreeter implements TestGreeter {
	@Override
	public String greet() {
		return "hello";
	}
}
