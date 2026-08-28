package com.feynman.rpc.core.extension;

public class HiGreeter implements TestGreeter {
	@Override
	public String greet() {
		return "hi";
	}
}
