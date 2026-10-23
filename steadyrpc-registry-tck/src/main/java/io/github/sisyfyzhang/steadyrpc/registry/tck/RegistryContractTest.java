package io.github.sisyfyzhang.steadyrpc.registry.tck;

import static io.github.sisyfyzhang.steadyrpc.registry.tck.PushRecorder.containsAddress;
import static io.github.sisyfyzhang.steadyrpc.registry.tck.PushRecorder.metadataOf;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.TestAbortedException;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceMetadata;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistrationListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.Registry;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryException;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * {@link Registry} 的契约测试套件（TCK）。每个实现在自己的 test 目录里继承本类，提供
 * {@link #factory()}、后端启停钩子 {@link #startBackend()} / {@link #stopBackend()}、可达与不可达两份配置，
 * 以及几个与后端一致性模型相关的时间窗（ZooKeeper 会话关闭即摘除，Nacos 靠心跳摘除，二者差一个数量级）。
 *
 * <p>为什么契约要可执行：Registry 是四个 SPI 里语义最多、最容易"看起来能用"的一个——首次推送、空列表、重复注册、
 * close 后是否还在回调、线程是否泄漏，任何一条不一致，消费端（ServiceDirectory 的推空保护、RpcClient 的连接池）
 * 都会在生产里以很难排查的方式出错。两个实现跑同一套用例，接口才保持中立（DECISIONS D-007）；
 * 第三方实现也能拿它自证。
 *
 * <p>用例设计约定：
 * <ul>
 *   <li>每个用例用随机的 {@link ServiceKey}，后端可以在类级别共享（Nacos 容器启动要几十秒）；</li>
 *   <li>提供者与订阅者用两个独立的 Registry 实例（两条会话），更接近真实部署，也覆盖跨会话可见性；</li>
 *   <li>契约只承诺"每次推送是全量列表"，不承诺推送次数，断言针对最近一次推送；</li>
 *   <li>"不再回调"类断言先用另一个活着的监听者证明事件确实发生了，避免空转通过。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class RegistryContractTest {

	private static final AtomicInteger PORTS = new AtomicInteger(20000);

	private final List<Registry> opened = new ArrayList<>();

	// ------------------------------------------------------------------ 实现方提供

	/** 被测实现的工厂。 */
	protected abstract RegistryFactory factory();

	/** 启动后端；后端不可用时用 {@link Assumptions} 中止，全部用例报告为 skipped 并带上原因。每个测试类调用一次。 */
	protected abstract void startBackend() throws Exception;

	/** 停止后端。即使 {@link #startBackend()} 被 assumption 跳过也会调用，实现需容忍"没启动"。 */
	protected abstract void stopBackend() throws Exception;

	/** 指向已启动后端的配置。 */
	protected abstract RegistryConfig config();

	/** 指向一个不可达后端的配置（例如本机一个没有监听的端口），用于验证失败不会无限阻塞。 */
	protected abstract RegistryConfig unreachableConfig();

	/**
	 * 持有注册的 Registry close 之后，其实例从其他订阅者视图中消失的最长时间。ZooKeeper 会话关闭即删临时节点；
	 * Nacos 取决于连接断开 / 心跳摘除。
	 */
	protected abstract Duration disappearanceWindow();

	/** 后端不可达时 {@link Registry#register} 抛 {@link RegistryException} 之前最多阻塞多久。 */
	protected abstract Duration registerFailureBound();

	/**
	 * 实现自己创建的线程的名字前缀，用于"close 后线程全部结束"的检查。返回空表示实现无法控制线程名
	 * （例如 Nacos 客户端），该用例被跳过并注明原因。
	 */
	protected Optional<String> threadNamePrefix() {
		return Optional.empty();
	}

	/** 一次变化传到订阅者的最长等待时间。 */
	protected Duration pushTimeout() {
		return Duration.ofSeconds(10);
	}

	/** 断言"不再回调"时观察多久。 */
	protected Duration quietPeriod() {
		return Duration.ofSeconds(1);
	}

	/**
	 * 后端能否在用例中途停止、再在<b>同一地址</b>恢复（恢复后数据可以是空的）。默认 false：
	 * {@code subscribeWhileBackendUnreachableThenRecovers} 被跳过并注明原因。返回 true 时必须实现
	 * {@link #stopBackendForRestart()} 与 {@link #restartBackend()}。
	 */
	protected boolean supportsBackendRestart() {
		return false;
	}

	/** 停止后端但保留地址（{@link #config()} 不变），之后 {@link #restartBackend()} 在同一地址恢复。 */
	protected void stopBackendForRestart() throws Exception {
		throw new UnsupportedOperationException("supportsBackendRestart() is false");
	}

	/** 在 {@link #stopBackendForRestart()} 之前的同一地址恢复后端。 */
	protected void restartBackend() throws Exception {
		throw new UnsupportedOperationException("supportsBackendRestart() is false");
	}

	/** 后端不可达时 {@link Registry#subscribe} 最多阻塞多久（实现的连接等待上限加调度余量）。 */
	protected Duration subscribeReturnBound() {
		return registerFailureBound();
	}

	// ------------------------------------------------------------------ 生命周期

	/** 后端不可用（startBackend 里的 assumption 失败）的原因；非 null 时每个用例以 skipped 结束。 */
	private String backendUnavailable;

	@BeforeAll
	final void tckStartBackend() throws Exception {
		try {
			startBackend();
		} catch (TestAbortedException e) {
			// 不让整个类在 @BeforeAll 中止：那样报告里只有"0 个测试"，看不出是跳过的。改为逐个用例 skipped 并带原因。
			backendUnavailable = e.getMessage();
		}
	}

	@BeforeEach
	final void tckRequireBackend() {
		Assumptions.assumeTrue(backendUnavailable == null, backendUnavailable);
	}

	@AfterAll
	final void tckStopBackend() throws Exception {
		stopBackend();
	}

	@AfterEach
	final void tckCloseRegistries() {
		for (Registry registry : opened) {
			try {
				registry.close();
			} catch (RuntimeException ignored) {
				// 清理阶段：个别用例故意关过它
			}
		}
		opened.clear();
	}

	/** 新建一个指向后端的 Registry，用例结束时自动关闭。 */
	protected final Registry newRegistry() {
		return track(factory().create(config()));
	}

	protected final Registry track(Registry registry) {
		opened.add(registry);
		return registry;
	}

	protected static ServiceKey randomKey() {
		return new ServiceKey("tck.Service" + UUID.randomUUID().toString().replace("-", ""), "1.0", "tck");
	}

	protected static ServiceInstance instance(ServiceKey key, int port, Map<String, String> metadata) {
		return new ServiceInstance(key, "127.0.0.1", port, metadata);
	}

	protected static ServiceInstance instance(ServiceKey key, int port) {
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(InstanceMetadata.WEIGHT, "1");
		metadata.put(InstanceMetadata.PROTOCOL, "2");
		metadata.put(InstanceMetadata.TIMESTAMP, "1700000000000");
		metadata.put(InstanceMetadata.PID, "4242");
		return instance(key, port, metadata);
	}

	/** 各用例端口互不相同：同一后端上残留的旧实例（例如心跳摘除较慢的 Nacos）不会被误认成本用例的。 */
	protected static int nextPort() {
		return PORTS.incrementAndGet();
	}

	// ------------------------------------------------------------------ 首次推送与变化推送

	@Test
	void subscribePushesTheCurrentListBeforeReturning_emptyWhenThereAreNoProviders() {
		Registry consumer = newRegistry();
		PushRecorder recorder = new PushRecorder("consumer");

		consumer.subscribe(randomKey(), recorder);

		assertNotNull(recorder.latest(), "subscribe must push the current list before returning");
		assertTrue(recorder.latest().isEmpty(), "no provider registered yet: " + recorder);
	}

	@Test
	void subscribeAfterRegisterPushesTheExistingInstanceBeforeReturning() {
		ServiceKey key = randomKey();
		ServiceInstance instance = instance(key, nextPort());
		Registry provider = newRegistry();
		provider.register(instance);
		// 跨会话可见是异步的：先用一个探测订阅确认后端已经可见，再验证"新订阅立即拿到它"
		PushRecorder probe = new PushRecorder("probe");
		newRegistry().subscribe(key, probe);
		probe.awaitLatest(pushTimeout(), "sees the instance", list -> containsAddress(list, instance.address()));

		Registry consumer = newRegistry();
		PushRecorder recorder = new PushRecorder("consumer");
		consumer.subscribe(key, recorder);

		assertNotNull(recorder.latest(), "subscribe must push before returning");
		assertTrue(containsAddress(recorder.latest(), instance.address()), recorder.toString());
	}

	@Test
	void registerIsPushedToSubscribers_unregisterIsPushedAsItsAbsence() {
		ServiceKey key = randomKey();
		ServiceInstance instance = instance(key, nextPort());
		Registry provider = newRegistry();
		Registry consumer = newRegistry();
		PushRecorder recorder = new PushRecorder("consumer");
		consumer.subscribe(key, recorder);

		provider.register(instance);
		List<ServiceInstance> afterRegister = recorder.awaitLatest(pushTimeout(), "contains registered instance",
				list -> containsAddress(list, instance.address()));
		assertEquals(1, afterRegister.size(), afterRegister.toString());
		assertEquals(key, afterRegister.get(0).key());

		provider.unregister(instance);
		recorder.awaitLatest(pushTimeout(), "no longer contains unregistered instance",
				list -> !containsAddress(list, instance.address()));

		// 摘除是幂等的：再摘一次、摘一个从没注册过的都不报错
		assertDoesNotThrow(() -> provider.unregister(instance));
		assertDoesNotThrow(() -> provider.unregister(instance(key, nextPort())));
	}

	// ------------------------------------------------------------------ 多监听者与取消订阅

	@Test
	void everyListenerOfTheSameServiceKeyReceivesPushes() {
		ServiceKey key = randomKey();
		ServiceInstance instance = instance(key, nextPort());
		Registry consumer = newRegistry();
		PushRecorder first = new PushRecorder("first");
		PushRecorder second = new PushRecorder("second");
		consumer.subscribe(key, first);
		consumer.subscribe(key, second);
		assertNotNull(first.latest());
		assertNotNull(second.latest());

		newRegistry().register(instance);

		first.awaitLatest(pushTimeout(), "contains instance", list -> containsAddress(list, instance.address()));
		second.awaitLatest(pushTimeout(), "contains instance", list -> containsAddress(list, instance.address()));
	}

	@Test
	void unsubscribeStopsCallbacksForThatListenerOnly() throws Exception {
		ServiceKey key = randomKey();
		ServiceInstance instance = instance(key, nextPort());
		Registry consumer = newRegistry();
		PushRecorder removed = new PushRecorder("removed");
		PushRecorder kept = new PushRecorder("kept");
		consumer.subscribe(key, removed);
		consumer.subscribe(key, kept);

		consumer.unsubscribe(key, removed);
		int removedCount = removed.count();
		newRegistry().register(instance);

		// kept 收到了，证明事件确实发生过；removed 在此之后再观察一个静默期
		kept.awaitLatest(pushTimeout(), "contains instance", list -> containsAddress(list, instance.address()));
		Thread.sleep(quietPeriod().toMillis());
		assertEquals(removedCount, removed.count(), "unsubscribed listener was called back: " + removed);
		// 取消一个从未订阅的监听者不报错
		assertDoesNotThrow(() -> consumer.unsubscribe(key, new PushRecorder("never")));
	}

	// ------------------------------------------------------------------ 元数据与重复注册

	@Test
	void metadataRoundTripsExactly() {
		ServiceKey key = randomKey();
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(InstanceMetadata.WEIGHT, "7");
		metadata.put(InstanceMetadata.PROTOCOL, "2");
		metadata.put(InstanceMetadata.TIMESTAMP, "1700000000123");
		metadata.put(InstanceMetadata.PID, "31337");
		// 编码敏感字符：查询串分隔符、空格、非 ASCII
		metadata.put("tags", "a&b=c d/灰度");
		ServiceInstance instance = instance(key, nextPort(), metadata);
		PushRecorder recorder = new PushRecorder("consumer");
		newRegistry().subscribe(key, recorder);

		newRegistry().register(instance);

		List<ServiceInstance> latest = recorder.awaitLatest(pushTimeout(), "contains instance",
				list -> containsAddress(list, instance.address()));
		assertEquals(metadata, metadataOf(latest, instance.address()));
		assertEquals(7, PushRecorder.byAddress(latest, instance.address()).weight());
	}

	@Test
	void reRegisteringTheSameAddressReplacesMetadataWithoutDuplicates() {
		ServiceKey key = randomKey();
		int port = nextPort();
		ServiceInstance v1 = instance(key, port, Map.of(InstanceMetadata.WEIGHT, "1", "rev", "1"));
		ServiceInstance v2 = instance(key, port, Map.of(InstanceMetadata.WEIGHT, "5", "rev", "2"));
		Registry provider = newRegistry();
		PushRecorder recorder = new PushRecorder("consumer");
		newRegistry().subscribe(key, recorder);

		provider.register(v1);
		recorder.awaitLatest(pushTimeout(), "has rev 1", list -> "1".equals(rev(list, v1.address())));
		provider.register(v1); // 完全相同的重复注册也是幂等的
		provider.register(v2);

		List<ServiceInstance> latest = recorder.awaitLatest(pushTimeout(), "has rev 2",
				list -> "2".equals(rev(list, v2.address())));
		assertEquals(1, latest.size(), "same address must not produce duplicate instances: " + latest);
		assertEquals(v2.metadata(), latest.get(0).metadata());
	}

	private static String rev(List<ServiceInstance> list, String address) {
		Map<String, String> metadata = metadataOf(list, address);
		return metadata == null ? null : metadata.get("rev");
	}

	@Test
	void serviceKeysDoNotLeakIntoEachOther() throws Exception {
		ServiceKey a = randomKey();
		ServiceKey b = randomKey();
		ServiceInstance onA = instance(a, nextPort());
		ServiceInstance onB = instance(b, nextPort());
		Registry consumer = newRegistry();
		PushRecorder recorderA = new PushRecorder("A");
		PushRecorder recorderB = new PushRecorder("B");
		consumer.subscribe(a, recorderA);
		consumer.subscribe(b, recorderB);
		Registry provider = newRegistry();

		provider.register(onA);
		recorderA.awaitLatest(pushTimeout(), "contains A's instance", list -> containsAddress(list, onA.address()));
		provider.register(onB);
		recorderB.awaitLatest(pushTimeout(), "contains B's instance", list -> containsAddress(list, onB.address()));
		Thread.sleep(quietPeriod().toMillis());

		for (List<ServiceInstance> push : recorderA.all()) {
			assertFalse(containsAddress(push, onB.address()), "A received B's instance: " + recorderA);
			assertTrue(push.stream().allMatch(i -> i.key().equals(a)), "A received a foreign key: " + recorderA);
		}
		for (List<ServiceInstance> push : recorderB.all()) {
			assertFalse(containsAddress(push, onA.address()), "B received A's instance: " + recorderB);
			assertTrue(push.stream().allMatch(i -> i.key().equals(b)), "B received a foreign key: " + recorderB);
		}
	}

	// ------------------------------------------------------------------ close

	@Test
	void instancesOfAClosedRegistryDisappearFromOtherSubscribersWithinTheBackendWindow() {
		ServiceKey key = randomKey();
		ServiceInstance instance = instance(key, nextPort());
		Registry provider = newRegistry();
		PushRecorder recorder = new PushRecorder("consumer");
		newRegistry().subscribe(key, recorder);
		provider.register(instance);
		recorder.awaitLatest(pushTimeout(), "contains instance", list -> containsAddress(list, instance.address()));

		long start = System.nanoTime();
		provider.close();
		recorder.awaitLatest(disappearanceWindow(), "instance gone after its registry closed",
				list -> !containsAddress(list, instance.address()));
		System.out.printf("[TCK %s] instance disappeared %dms after its registry closed (window %dms)%n",
				getClass().getSimpleName(), (System.nanoTime() - start) / 1_000_000, disappearanceWindow().toMillis());
	}

	@Test
	void aClosedRegistryNeverCallsItsListenersAgain() throws Exception {
		ServiceKey key = randomKey();
		ServiceInstance instance = instance(key, nextPort());
		Registry closing = newRegistry();
		PushRecorder silenced = new PushRecorder("silenced");
		closing.subscribe(key, silenced);
		PushRecorder witness = new PushRecorder("witness");
		newRegistry().subscribe(key, witness);

		closing.close();
		int countAtClose = silenced.count();
		newRegistry().register(instance);

		witness.awaitLatest(pushTimeout(), "contains instance", list -> containsAddress(list, instance.address()));
		Thread.sleep(quietPeriod().toMillis());
		assertEquals(countAtClose, silenced.count(), "listener of a closed registry was called back: " + silenced);
	}

	@Test
	void closeIsIdempotent_andRegisterOrSubscribeAfterCloseThrowIllegalState() {
		Registry registry = newRegistry();
		ServiceKey key = randomKey();
		ServiceInstance instance = instance(key, nextPort());

		registry.close();
		assertDoesNotThrow(registry::close);

		assertThrows(IllegalStateException.class, () -> registry.register(instance));
		assertThrows(IllegalStateException.class, () -> registry.subscribe(key, new PushRecorder("late")));
		// 清理动作在关闭后静默返回
		assertDoesNotThrow(() -> registry.unregister(instance));
		assertDoesNotThrow(() -> registry.unsubscribe(key, new PushRecorder("late")));
	}

	// ------------------------------------------------------------------ 注册丢失回调（可选能力）

	/**
	 * {@link Registry#addRegistrationListener} 是可选能力：不实现它的 Registry（缺省方法返回 false、永不回调）行为不变；
	 * 实现了的，在正常的注册 / 订阅 / 摘除 / 关闭过程中不得误报丢失或恢复——误报会让 RpcServer 的 isRegistered()
	 * 与 health 组件在一切正常时报"不可被发现"。真正的丢失（会话过期）与后端强相关，由各实现自己的测试覆盖。
	 */
	@Test
	void registrationListenerIsOptional_andNormalOperationsNeverReportLossOrRestore() throws Exception {
		ServiceKey key = randomKey();
		ServiceInstance instance = instance(key, nextPort());
		Registry provider = newRegistry();
		List<String> events = new CopyOnWriteArrayList<>();
		boolean supported = provider.addRegistrationListener(new RegistrationListener() {
			@Override
			public void onRegistrationsLost() {
				events.add("lost");
			}

			@Override
			public void onRegistrationsRestored() {
				events.add("restored");
			}
		});
		PushRecorder witness = new PushRecorder("witness");
		newRegistry().subscribe(key, witness);

		provider.register(instance);
		witness.awaitLatest(pushTimeout(), "contains instance", list -> containsAddress(list, instance.address()));
		provider.subscribe(key, new PushRecorder("self"));
		provider.unregister(instance);
		witness.awaitLatest(pushTimeout(), "instance removed", list -> !containsAddress(list, instance.address()));
		provider.close();
		Thread.sleep(quietPeriod().toMillis());

		assertEquals(List.of(), events, "normal operations must not report registration loss / restore");
		System.out.printf("[TCK %s] registration listener supported=%s, events during normal operations=%s%n",
				getClass().getSimpleName(), supported, events);
	}

	@Test
	void threadsCreatedByTheImplementationEndWithinFiveSecondsAfterClose() {
		Optional<String> prefix = threadNamePrefix();
		Assumptions.assumeTrue(prefix.isPresent(),
				getClass().getSimpleName() + ": implementation does not control its thread names, thread-leak check skipped");
		Set<Thread> before = liveThreads(prefix.get());

		Registry registry = newRegistry();
		ServiceKey key = randomKey();
		registry.subscribe(key, new PushRecorder("consumer"));
		registry.register(instance(key, nextPort()));
		Set<Thread> created = liveThreads(prefix.get());
		created.removeAll(before);
		// 先证明快照能看到实现的线程，"关闭后为空"才有意义
		assertFalse(created.isEmpty(), "no live thread named " + prefix.get() + "* while the registry is open");
		System.out.printf("[TCK %s] threads while open: %s%n", getClass().getSimpleName(),
				created.stream().map(Thread::getName).sorted().toList());

		registry.close();
		await("threads " + created + " to end").atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
				.until(() -> created.stream().noneMatch(Thread::isAlive));
	}

	private static Set<Thread> liveThreads(String prefix) {
		Set<Thread> threads = new HashSet<>();
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.isAlive() && thread.getName().startsWith(prefix)) {
				threads.add(thread);
			}
		}
		return threads;
	}

	// ------------------------------------------------------------------ 后端不可达

	/**
	 * 统一的订阅语义（Registry#subscribe 的 javadoc）：后端不可达时订阅不抛异常、在声明的上限内返回、不推空列表；
	 * 后端恢复后补推首次列表，并照常收到之后的变化。提供者在后端恢复之后才注册，所以"收到正确列表"不依赖后端
	 * 重启后是否保留了旧数据。
	 */
	@Test
	void subscribeWhileBackendUnreachableThenRecovers() throws Exception {
		Assumptions.assumeTrue(supportsBackendRestart(),
				getClass().getSimpleName() + ": implementation's test backend cannot be restarted in place, case skipped");
		ServiceKey key = randomKey();
		ServiceInstance instance = instance(key, nextPort());
		PushRecorder recorder = new PushRecorder("consumer");
		Registry consumer;
		stopBackendForRestart();
		try {
			consumer = newRegistry();
			long start = System.nanoTime();
			assertDoesNotThrow(() -> consumer.subscribe(key, recorder), "subscribe must not throw while the backend is unreachable");
			long elapsed = (System.nanoTime() - start) / 1_000_000;
			System.out.printf("[TCK %s] subscribe returned after %dms with the backend down (bound %dms), pushes so far: %s%n",
					getClass().getSimpleName(), elapsed, subscribeReturnBound().toMillis(), recorder);
			assertTrue(elapsed <= subscribeReturnBound().toMillis(),
					"subscribe blocked " + elapsed + "ms, declared bound " + subscribeReturnBound().toMillis() + "ms");
			Thread.sleep(quietPeriod().toMillis());
			assertTrue(recorder.all().stream().noneMatch(List::isEmpty),
					"'not connected' must not be pushed as 'no providers': " + recorder);
		} finally {
			restartBackend();
		}
		long recoveredAt = System.nanoTime();
		Registry provider = newRegistry();
		await("register after the backend recovered").atMost(pushTimeout()).pollInterval(Duration.ofMillis(100))
				.ignoreExceptions().until(() -> {
					provider.register(instance);
					return true;
				});
		recorder.awaitLatest(pushTimeout(), "catches up after the backend recovered",
				list -> containsAddress(list, instance.address()));
		System.out.printf("[TCK %s] subscriber saw the provider %dms after the backend restarted; pushes: %s%n",
				getClass().getSimpleName(), (System.nanoTime() - recoveredAt) / 1_000_000, recorder);
	}

	/**
	 * 与上一条同一契约，但后端停机时间超过客户端内部重试的放弃点（{@link #clientRetryHorizon()}）：订阅时发出的后台操作
	 * 已经"重试放弃"，后端此后才恢复，订阅仍须补推首次列表并收到之后的变化——不能依赖某一次后台操作恰好在停机期间
	 * 没有耗尽重试。提供者在后端恢复之后才注册。
	 */
	@Test
	void subscribeSurvivesTheClientGivingUpRetriesBeforeTheBackendAppears() throws Exception {
		Assumptions.assumeTrue(supportsBackendRestart(),
				getClass().getSimpleName() + ": implementation's test backend cannot be restarted in place, case skipped");
		ServiceKey key = randomKey();
		ServiceInstance instance = instance(key, nextPort());
		PushRecorder recorder = new PushRecorder("consumer");
		stopBackendForRestart();
		long downFor;
		try {
			Registry consumer = newRegistry();
			assertDoesNotThrow(() -> consumer.subscribe(key, recorder), "subscribe must not throw while the backend is unreachable");
			long subscribedAt = System.nanoTime();
			Thread.sleep(clientRetryHorizon().toMillis());
			downFor = (System.nanoTime() - subscribedAt) / 1_000_000;
			assertTrue(recorder.all().stream().noneMatch(List::isEmpty),
					"'not connected' must not be pushed as 'no providers': " + recorder);
		} finally {
			restartBackend();
		}
		long recoveredAt = System.nanoTime();
		Registry provider = newRegistry();
		await("register after the backend recovered").atMost(pushTimeout()).pollInterval(Duration.ofMillis(100))
				.ignoreExceptions().until(() -> {
					provider.register(instance);
					return true;
				});
		recorder.awaitLatest(pushTimeout(), "catches up although the client gave up its retries before the backend appeared",
				list -> containsAddress(list, instance.address()));
		System.out.printf("[TCK %s] backend down for %dms after subscribe (retry horizon %dms); subscriber saw the provider %dms after "
				+ "the backend restarted; pushes: %s%n", getClass().getSimpleName(), downFor, clientRetryHorizon().toMillis(),
				(System.nanoTime() - recoveredAt) / 1_000_000, recorder);
	}

	/**
	 * 客户端内部对单次后台操作的重试在多久之后放弃（后端一直不可达时）。{@code subscribeSurvivesTheClientGivingUpRetries...}
	 * 让后端停机这么久再恢复。缺省 0：实现没有"放弃"这回事（例如进程内参考实现），停机时长退化为 0。
	 */
	protected Duration clientRetryHorizon() {
		return Duration.ZERO;
	}

	@Test
	void registerAgainstAnUnreachableBackendFailsWithRegistryExceptionWithinTheDeclaredBound() {
		long createStart = System.nanoTime();
		Registry registry = track(factory().create(unreachableConfig()));
		long createMillis = (System.nanoTime() - createStart) / 1_000_000;
		ServiceInstance instance = instance(randomKey(), nextPort());

		long start = System.nanoTime();
		assertThrows(RegistryException.class, () -> registry.register(instance));
		long elapsed = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[TCK %s] create against unreachable backend took %dms, register failed after %dms (bound %dms)%n",
				getClass().getSimpleName(), createMillis, elapsed, registerFailureBound().toMillis());
		assertTrue(elapsed <= registerFailureBound().toMillis(),
				"register blocked " + elapsed + "ms, declared bound " + registerFailureBound().toMillis() + "ms");
	}
}
