package com.feynman.rpc.benchmark;

import java.util.ArrayList;
import java.util.List;

/** 模拟典型 RPC 参数/返回值形态的占位数据——一个带嵌套 List 的小型 POJO。 */
public class SampleUser {

	private Integer id;
	private String name;
	private String email;
	private int age;
	private List<String> roles;

	public SampleUser() {
	}

	public static SampleUser sample() {
		SampleUser u = new SampleUser();
		u.id = 1001;
		u.name = "zzz";
		u.email = "zzz@example.com";
		u.age = 29;
		u.roles = new ArrayList<>(List.of("admin", "operator", "viewer"));
		return u;
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

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public int getAge() {
		return age;
	}

	public void setAge(int age) {
		this.age = age;
	}

	public List<String> getRoles() {
		return roles;
	}

	public void setRoles(List<String> roles) {
		this.roles = roles;
	}
}
