package io.github.sisyfyzhang.steadyrpc.examples;

import java.util.concurrent.CountDownLatch;

import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingServer;

/**
 * 只用于本机试跑示例的进程内 ZooKeeper（curator-test 的 TestingServer），省去安装 ZooKeeper 或 Docker。
 * 默认监听 2181（与 provider.yml / consumer.yml 一致），参数可指定端口；Ctrl+C 退出。数据在临时目录，退出即删除。
 * 生产与长期使用请用真实的 ZooKeeper（例如 {@code docker run -d -p 2181:2181 zookeeper:3.9.6}）。
 */
public final class EmbeddedZooKeeper {

	private EmbeddedZooKeeper() {
	}

	public static void main(String[] args) throws Exception {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 2181;
		TestingServer server = new TestingServer(new InstanceSpec(null, port, -1, -1, true, -1, 2000, -1), true);
		CountDownLatch stopped = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				server.close();
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				stopped.countDown();
			}
		}, "embedded-zookeeper-shutdown"));
		System.out.println("Embedded ZooKeeper listening on " + server.getConnectString() + " (Ctrl+C to stop)");
		stopped.await();
	}
}
