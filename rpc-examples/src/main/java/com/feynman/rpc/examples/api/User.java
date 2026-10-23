package com.feynman.rpc.examples.api;

/**
 * 示例 DTO。经序列化器传输的类型用普通类（无参构造 + 可变字段）而不是 record：
 * Protostuff 1.8 无法还原 record。
 */
public class User {

	private Integer id;
	private String name;

	public User() {
	}

	public User(Integer id, String name) {
		this.id = id;
		this.name = name;
	}

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return "User{id=" + id + ", name=" + name + "}";
	}
}
