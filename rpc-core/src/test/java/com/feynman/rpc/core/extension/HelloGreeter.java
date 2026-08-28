package com.feynman.rpc.core.extension;

public class HelloGreeter implements TestGreeter {
	@Override
	public String greet() {
		return "hello";
	}
}
