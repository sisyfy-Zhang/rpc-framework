package com.feynman.rpc.registry.nacos;

/**
 * 把本项目 ZK 风格的路径模型映射到 Nacos 扁平的 service/instance 模型：Nacos 没有 ZK 那种
 * 层级化 znode 树的概念，只有一个"服务名"加一份实例列表。像 {@code /rpc/192.168.1.1:9000#1}
 * 这样的注册路径会拆成服务名 {@code "rpc"} 和实例 {@code host=192.168.1.1, port=9000, weight=1}。
 * 特意做成一个无依赖的独立类，方便不接触真实 Nacos SDK 也能单元测试。
 */
final class NacosPath {

	final String serviceName;
	final String host;
	final int port;
	final int weight;

	private NacosPath(String serviceName, String host, int port, int weight) {
		this.serviceName = serviceName;
		this.host = host;
		this.port = port;
		this.weight = weight;
	}

	/** 把完整注册路径（如 {@code /rpc/192.168.1.1:9000#1}）拆分成各个部分。 */
	static NacosPath parse(String path) {
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash < 0) {
			throw new IllegalArgumentException("Not a valid registration path: " + path);
		}
		String basePath = path.substring(0, lastSlash);
		String node = path.substring(lastSlash + 1);

		String[] hostPortAndWeight = node.split("#");
		String[] hostPort = hostPortAndWeight[0].split(":");
		if (hostPort.length != 2) {
			throw new IllegalArgumentException("Not a valid host:port#weight node: " + node);
		}
		String host = hostPort[0];
		int port = Integer.parseInt(hostPort[1]);
		int weight = hostPortAndWeight.length > 1 ? Integer.parseInt(hostPortAndWeight[1]) : 1;

		return new NacosPath(serviceNameOf(basePath), host, port, weight);
	}

	/** 从形如 {@code /rpc} 的 basePath 推导出 Nacos 服务名（用于 getChildren/subscribe）。 */
	static String serviceNameOf(String basePath) {
		String trimmed = basePath.startsWith("/") ? basePath.substring(1) : basePath;
		return trimmed.isEmpty() ? "rpc-default" : trimmed;
	}

	/** 格式化回和 ZK 子节点名一样的 {@code host:port#weight} 形式，
	 * 让下游代码（如 {@code TCPClient}）不需要关心当前用的是哪个注册中心后端。 */
	static String toChildName(String host, int port, int weight) {
		return host + ":" + port + "#" + weight;
	}
}
