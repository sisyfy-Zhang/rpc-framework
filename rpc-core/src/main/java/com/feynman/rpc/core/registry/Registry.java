package com.feynman.rpc.core.registry;

import com.feynman.rpc.core.ServiceKey;

/**
 * 一个到注册中心的有状态会话（连接、会话、订阅都挂在它上面）。由 {@link RegistryFactory} 按
 * {@link RegistryConfig} 创建，归创建它的 RpcClient / RpcServer 独占持有并在其 close() 时关闭——
 * 而不是像旧实现那样由 SPI 缓存一个进程级单例，导致同一 JVM 里两个实例共享一条 ZK 会话、
 * 一个关闭就把另一个的注册也带走。
 *
 * <p>按接口（{@link ServiceKey}）注册与订阅，节点携带元数据（权重、协议版本、启动时间、进程号）。
 *
 * <p>本接口的行为契约由 rpc-registry-tck 模块的 {@code RegistryContractTest} 定义并可执行验证，第三方实现
 * 继承它即可跑同一套用例。要点：
 * <ul>
 *   <li>{@link RegistryFactory#create} 不应等待后端连上——注册中心故障不该让 RpcServer / RpcClient 的构造或启动卡住；</li>
 *   <li>后端不可达时 {@link #register} 在实现声明的时限内抛 {@link RegistryException}（由 RpcServer 退避重试）；</li>
 *   <li>后端不可达时 {@link #subscribe} 不抛异常、在实现声明的等待上限内返回、不推空列表，连上后补推首次全量列表；</li>
 *   <li>同一地址重复 register 以最新元数据为准，不产生重复实例；</li>
 *   <li>实现只忠实转发后端看到的列表（包括空列表）；推空保护是消费端 ServiceDirectory 的策略；</li>
 *   <li>close 之后不再回调任何监听者、实现自己的线程在 5 秒内结束；close 可重复调用；
 *       close 之后 register / subscribe 抛 {@link IllegalStateException}，unregister / unsubscribe 静默返回
 *       （它们是清理动作，清理"已经关掉的东西"不该报错）。</li>
 * </ul>
 */
public interface Registry extends AutoCloseable {

	/**
	 * 注册一个提供者节点；节点生命周期绑定本会话，进程消失时节点应随之消失。幂等：同一地址再次注册时以新元数据为准。
	 *
	 * @throws RegistryException 后端不可达或拒绝，且在实现声明的时限内（不无限阻塞）
	 * @throws IllegalStateException 已 close
	 */
	void register(ServiceInstance instance);

	/** 摘除此前注册的节点；节点不存在时静默返回（摘除是幂等的下线步骤，不该因为"已经没了"而失败）。 */
	void unregister(ServiceInstance instance);

	/**
	 * 订阅某个服务的提供者列表。{@code listener} 在订阅时<b>立即</b>（本方法返回之前）收到一次当前全量列表，
	 * 之后每次变化再收到一次全量列表——消费端只处理"现在是什么样"，不需要拼接增量事件。
	 *
	 * <p>后端暂时不可达时（两个实现统一的语义，由 rpc-registry-tck 的
	 * {@code subscribeWhileBackendUnreachableThenRecovers} 验证）：
	 * <ul>
	 *   <li><b>不抛异常</b>：注册中心暂时不可达是注册中心实现自己要消化的状态，抛给调用方（RpcClient.reference）
	 *       只会让每个调用方各写一套重试；</li>
	 *   <li><b>有限等待</b>：最多阻塞实现声明的等待上限（ZooKeeper / Nacos 的 {@code connect-wait-ms}）后返回，
	 *       不允许无限阻塞调用线程；</li>
	 *   <li><b>不推空列表</b>："没连上"不等于"没有提供者"，推空会让消费端误以为服务全部下线；</li>
	 *   <li><b>连上后补推</b>：后台持续重试，连上后把首次全量列表推给监听者，之后照常推送变化。</li>
	 * </ul>
	 *
	 * @throws IllegalStateException 已 close
	 */
	void subscribe(ServiceKey key, InstanceListener listener);

	/** 取消订阅；本方法返回之后 {@code listener} 不会再被回调，同一服务的其他监听者不受影响。 */
	void unsubscribe(ServiceKey key, InstanceListener listener);

	/** 关闭会话：注册过的临时节点随会话消失，所有订阅停止回调，实现自己的线程结束。可重复调用。 */
	@Override
	void close();
}
