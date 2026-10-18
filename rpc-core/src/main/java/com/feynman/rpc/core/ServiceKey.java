package com.feynman.rpc.core;

/**
 * 服务身份 = 接口 + 版本 + 分组。注册、订阅、路由都以它为键，而不是以"进程"为键——
 * 旧实现所有 provider 都注册到同一个 /rpc 路径下，一个进程只要暴露了任意服务就会被当成
 * 所有服务的提供者，多 provider 场景必然路由错。
 *
 * <p>字符串形式 {@code interface:version:group}，缺省 version 为空串、group 为 {@code default}：
 * 灰度（version）和环境隔离（group）是线 B 的硬需求，但绝大多数调用不关心它们，所以必须有
 * 不需要显式填写的默认值。{@code :} 是分隔符、{@code /} 会破坏 ZooKeeper 路径，因此三个分量里都禁止出现。
 */
public record ServiceKey(String interfaceName, String version, String group) {

	public static final String DEFAULT_GROUP = "default";

	public ServiceKey {
		if (interfaceName == null || interfaceName.isBlank()) {
			throw new IllegalArgumentException("interfaceName must not be blank");
		}
		interfaceName = interfaceName.trim();
		version = version == null ? "" : version.trim();
		group = (group == null || group.isBlank()) ? DEFAULT_GROUP : group.trim();
		checkPart("interfaceName", interfaceName);
		checkPart("version", version);
		checkPart("group", group);
	}

	public static ServiceKey of(Class<?> itf) {
		return new ServiceKey(itf.getName(), null, null);
	}

	public static ServiceKey of(Class<?> itf, String version, String group) {
		return new ServiceKey(itf.getName(), version, group);
	}

	/** {@link #toString()} 的逆操作；注册中心实现从路径 / 服务名还原键时使用。 */
	public static ServiceKey parse(String text) {
		String[] parts = text.split(":", -1);
		if (parts.length != 3) {
			throw new IllegalArgumentException("Not a service key (interface:version:group): " + text);
		}
		return new ServiceKey(parts[0], parts[1], parts[2]);
	}

	@Override
	public String toString() {
		return interfaceName + ":" + version + ":" + group;
	}

	private static void checkPart(String name, String value) {
		if (value.indexOf(':') >= 0 || value.indexOf('/') >= 0) {
			throw new IllegalArgumentException(name + " must not contain ':' or '/': " + value);
		}
	}
}
