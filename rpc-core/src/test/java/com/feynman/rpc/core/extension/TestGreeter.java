package com.feynman.rpc.core.extension;

@SPI("hello")
public interface TestGreeter {
	String greet();
}
