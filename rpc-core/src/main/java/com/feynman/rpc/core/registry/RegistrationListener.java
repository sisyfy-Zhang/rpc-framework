package com.feynman.rpc.core.registry;

/**
 * {@link Registry#addRegistrationListener} 的回调：本会话注册过的节点在注册中心丢失 / 重建。
 *
 * <p>为什么需要它：注册成功不等于之后一直可被发现。ZooKeeper 会话过期时临时节点随旧会话消失，直到实现在新会话上
 * 重建之前，消费端都发现不了这个进程——而端口照常在服务。RpcServer 据此让 {@code isRegistered()}（以及 health
 * 组件 {@code rpc}）在这段时间里如实报告"不可被发现"。
 *
 * <p>两个事件成对出现：一次 lost 之后最多一次 restored，restored 之前不会再有 lost。回调在实现自己的线程上执行，
 * 不应阻塞。
 */
public interface RegistrationListener {

	/** 已注册的节点不再存在于注册中心（例如会话过期）；实现会在恢复连接后自行重建。 */
	void onRegistrationsLost();

	/** 丢失之后，全部已注册节点已在新会话上重建完成。 */
	void onRegistrationsRestored();
}
