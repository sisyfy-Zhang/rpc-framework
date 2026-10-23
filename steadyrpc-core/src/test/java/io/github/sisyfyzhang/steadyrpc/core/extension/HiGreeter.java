package io.github.sisyfyzhang.steadyrpc.core.extension;

public class HiGreeter implements TestGreeter {
	@Override
	public String greet() {
		return "hi";
	}
}
