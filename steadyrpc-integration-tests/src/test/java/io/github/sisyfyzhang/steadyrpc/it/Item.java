package io.github.sisyfyzhang.steadyrpc.it;

import java.util.List;
import java.util.Objects;

/**
 * 集成测试用 DTO：普通类（无参构造 + getter/setter），两种序列化器都能处理；全部是引用类型字段，
 * 用来验证"全 null 字段的对象"在 Protostuff 下编码为 0 字节时仍还原成非 null 对象。
 */
public class Item {

	private Integer id;
	private String name;
	private List<String> tags;

	public Item() {
	}

	public Item(Integer id, String name, List<String> tags) {
		this.id = id;
		this.name = name;
		this.tags = tags;
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

	public List<String> getTags() {
		return tags;
	}

	public void setTags(List<String> tags) {
		this.tags = tags;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Item other && Objects.equals(id, other.id) && Objects.equals(name, other.name)
				&& Objects.equals(tags, other.tags);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, name, tags);
	}

	@Override
	public String toString() {
		return "Item{" + id + "," + name + "," + tags + "}";
	}
}
