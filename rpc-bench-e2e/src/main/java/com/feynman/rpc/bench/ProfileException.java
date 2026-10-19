package com.feynman.rpc.bench;

import java.util.List;

/** 口径卡不合法。{@link #problems()} 每条形如 {@code load.rate: required when load.model = open}。 */
final class ProfileException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final List<String> problems;

	ProfileException(String source, List<String> problems) {
		super("invalid profile " + source + ":\n  - " + String.join("\n  - ", problems));
		this.problems = List.copyOf(problems);
	}

	List<String> problems() {
		return problems;
	}
}
