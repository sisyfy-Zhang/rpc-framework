package io.github.sisyfyzhang.steadyrpc.registry.zookeeper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.apache.curator.utils.DefaultZookeeperFactory;
import org.apache.curator.utils.ZookeeperFactory;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;

/**
 * 让 ZooKeeper 客户端自己的 SendThread / EventThread 带上注册中心实例的前缀。
 *
 * <p>为什么需要：Curator 的 {@code threadFactory} 只管 Curator 自己的线程；ZooKeeper 客户端（{@code ClientCnxn}）
 * 的两个线程名取自<b>构造 ZooKeeper 对象的那个线程</b>的名字加后缀（{@code main-SendThread(host:port)}、
 * {@code main-EventThread}），而构造发生在调用 {@code client.start()} 的线程、或会话过期后 Curator 内部重建连接的线程上。
 * 不处理的话线程快照里看不出它们属于哪个 Registry，关闭后的"无残留"断言也覆盖不到。
 *
 * <p>做法：每次都在一个按实例命名的短命线程上构造 ZooKeeper（构造只起线程、不做网络 I/O，耗时可忽略），
 * 不去改调用方线程的名字。
 */
final class NamedZookeeperFactory implements ZookeeperFactory {

	private final ZookeeperFactory delegate = new DefaultZookeeperFactory();
	private final String threadName;

	NamedZookeeperFactory(String threadName) {
		this.threadName = threadName;
	}

	@Override
	public ZooKeeper newZooKeeper(String connectString, int sessionTimeout, Watcher watcher, boolean canBeReadOnly)
			throws Exception {
		return onNamedThread(() -> delegate.newZooKeeper(connectString, sessionTimeout, watcher, canBeReadOnly));
	}

	@Override
	public ZooKeeper newZooKeeper(String connectString, int sessionTimeout, Watcher watcher, boolean canBeReadOnly,
			ZKClientConfig zkClientConfig) throws Exception {
		return onNamedThread(() -> delegate.newZooKeeper(connectString, sessionTimeout, watcher, canBeReadOnly, zkClientConfig));
	}

	private ZooKeeper onNamedThread(Callable<ZooKeeper> construct) throws Exception {
		FutureTask<ZooKeeper> task = new FutureTask<>(construct);
		Thread thread = new Thread(task, threadName);
		thread.setDaemon(true);
		thread.start();
		// 不可中断地等待：构造很快，而中途放弃会让已经起来的 ZooKeeper 线程无人关闭
		boolean interrupted = false;
		while (true) {
			try {
				thread.join();
				break;
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
		try {
			return task.get();
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception exception) {
				throw exception;
			}
			throw e;
		}
	}
}
