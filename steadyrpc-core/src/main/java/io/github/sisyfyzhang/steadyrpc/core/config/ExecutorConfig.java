package io.github.sisyfyzhang.steadyrpc.core.config;

/**
 * 服务端业务执行模型。为什么做成 sealed：执行模型只有框架认识的有限几种，新增一种必须同时改
 * 服务端的创建逻辑——sealed + 模式匹配 switch 让编译器检查这一点，而不是靠 default 分支兜底。
 *
 * <ul>
 *   <li>{@link Fixed}：固定大小线程池 + <b>有界</b>队列，阻塞式业务代码的传统选择，线程数即并发上限。</li>
 *   <li>{@link VirtualThreads}：每请求一个虚拟线程（JDK 21），阻塞式业务也能获得接近异步的吞吐，同时执行的请求数
 *       用信号量封顶；注意 JDK 21 上 synchronized 会钉住载体线程。阶段 4 的"虚拟线程 vs 固定池"实验靠这个开关。</li>
 *   <li>{@link Direct}（<b>opt-in，不是缺省</b>）：业务方法直接在收到请求的 IO 事件循环线程上执行，没有队列、没有线程交接。
 *       <b>业务方法阻塞会卡住同一事件循环上的所有连接，仅适用于纯 CPU、微秒级的方法；与 gRPC directExecutor 同类。</b>
 *       见 {@link #direct()}。</li>
 * </ul>
 *
 * <p><b>为什么必须有界</b>：无界队列在过载时不会拒绝任何请求，只会让排队时间无限变长——调用方早已超时放弃，
 * 服务端却还在排队、还在占内存，最后整个进程被拖垮。有界之后，超出容量的请求立刻收到 SERVER_OVERLOADED
 * （业务方法没有执行），客户端可以安全地换节点重试或快速失败；排队中过期的请求另有 deadline 检查兜底。
 * 两个上限都只约束"占着执行器的请求"：业务方法返回未完成的 CompletableFuture 后线程立即释放（服务端异步到底），
 * 等待中的 future 不占名额。
 */
public sealed interface ExecutorConfig permits ExecutorConfig.Fixed, ExecutorConfig.VirtualThreads, ExecutorConfig.Direct {

	/** 固定池默认排队上限：按"线程数 × 单请求耗时"量级估计的突发缓冲，不是吞吐调优参数。 */
	int DEFAULT_QUEUE_CAPACITY = 1000;

	/** 虚拟线程默认同时执行上限：虚拟线程本身很便宜，这个上限防的是它们背后的下游资源（连接池、内存）被打爆。 */
	int DEFAULT_MAX_CONCURRENT = 10_000;

	/** 固定池，排队上限 {@link #DEFAULT_QUEUE_CAPACITY}。 */
	static ExecutorConfig fixed(int threads) {
		return new Fixed(threads, DEFAULT_QUEUE_CAPACITY);
	}

	/**
	 * 固定池，指定排队上限。{@code queueCapacity = 0} 表示不排队：所有线程都忙时新请求直接被拒绝。
	 */
	static ExecutorConfig fixed(int threads, int queueCapacity) {
		return new Fixed(threads, queueCapacity);
	}

	/** 虚拟线程，同时执行上限 {@link #DEFAULT_MAX_CONCURRENT}。 */
	static ExecutorConfig virtualThreads() {
		return new VirtualThreads(DEFAULT_MAX_CONCURRENT);
	}

	/** 虚拟线程，指定同时执行上限（超过时新请求被拒绝，回 SERVER_OVERLOADED）。 */
	static ExecutorConfig virtualThreads(int maxConcurrent) {
		return new VirtualThreads(maxConcurrent);
	}

	/**
	 * 在 IO 线程上直接执行业务方法（opt-in），在途上限 {@link #DEFAULT_MAX_CONCURRENT}，见 {@link #direct(int)}。
	 *
	 * <p><b>警示：业务方法阻塞会卡住同一事件循环上的所有连接，仅适用于纯 CPU、微秒级的方法；与 gRPC directExecutor 同类。</b>
	 * 在业务方法里发起同步 RPC、读数据库、睡眠、等锁都算阻塞；返回 {@code CompletableFuture} 的异步方法可以用，
	 * 但 future 的完成回调（结果序列化、回包）在完成它的线程上执行。缺省执行器不变（{@link #defaults()} 仍是固定池）。
	 */
	static ExecutorConfig direct() {
		return new Direct(DEFAULT_MAX_CONCURRENT);
	}

	/**
	 * 在 IO 线程上直接执行业务方法（opt-in），指定在途上限。
	 *
	 * <p>{@code maxConcurrent} 约束的是<b>在途请求</b>（已开始处理、回包尚未交给 Netty）：同步方法在 IO 线程上一个接一个执行，
	 * 同时执行的数目天然不超过 IO 线程数，这个上限实际约束的是返回未完成 future 的异步方法——它们不占 IO 线程，
	 * 不封顶就会无限堆积。超过上限的请求回 SERVER_OVERLOADED（业务方法没有执行）。缺省值与虚拟线程模式相同
	 * （{@link #DEFAULT_MAX_CONCURRENT}），理由也相同：防的是下游资源被打爆，不是线程数。
	 *
	 * <p><b>警示：业务方法阻塞会卡住同一事件循环上的所有连接，仅适用于纯 CPU、微秒级的方法；与 gRPC directExecutor 同类。</b>
	 */
	static ExecutorConfig direct(int maxConcurrent) {
		return new Direct(maxConcurrent);
	}

	/** 缺省：CPU 核数 × 2 的固定池，沿用旧实现的经验值。 */
	static ExecutorConfig defaults() {
		return fixed(Runtime.getRuntime().availableProcessors() * 2);
	}

	record Fixed(int threads, int queueCapacity) implements ExecutorConfig {
		public Fixed {
			if (threads < 1) {
				throw new IllegalArgumentException("threads must be >= 1: " + threads);
			}
			if (queueCapacity < 0) {
				throw new IllegalArgumentException("queueCapacity must be >= 0: " + queueCapacity);
			}
		}
	}

	record VirtualThreads(int maxConcurrent) implements ExecutorConfig {
		public VirtualThreads {
			if (maxConcurrent < 1) {
				throw new IllegalArgumentException("maxConcurrent must be >= 1: " + maxConcurrent);
			}
		}
	}

	/** 见 {@link ExecutorConfig#direct(int)}：业务方法在 IO 线程上执行，{@code maxConcurrent} 约束在途请求数。 */
	record Direct(int maxConcurrent) implements ExecutorConfig {
		public Direct {
			if (maxConcurrent < 1) {
				throw new IllegalArgumentException("maxConcurrent must be >= 1: " + maxConcurrent);
			}
		}
	}
}
