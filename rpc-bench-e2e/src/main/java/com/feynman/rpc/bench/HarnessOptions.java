package com.feynman.rpc.bench;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code run} 的 harness 实现选项（不属于口径：口径卡描述"压什么、怎么算"，这里是"harness 用什么方式发出去"）。
 * 每一项都如实进 JSON 的 {@code run.harness_options}，Markdown 口径表单列一行；缺省值的依据见
 * docs/probes/harness-tail-latency.md。
 *
 * @param openWorkers 开环把计划时刻交给谁执行：{@link #WORKERS_PER_REQUEST}（每个请求一个新虚拟线程）或
 *                    {@link #WORKERS_POOL}（固定数量的虚拟线程工作者从有界 FIFO 队列取计划时刻）或
 *                    {@link #WORKERS_POOL_PLATFORM}（同上，工作者是平台线程）
 * @param poolSize    {@code pool} 的工作者数；null = 缺省（http 卡 = {@code target.http.connections}，rpc 卡 = {@value #DEFAULT_RPC_POOL_SIZE}）
 * @param ioThreads   HTTP 客户端事件循环线程数；null = 缺省（http 开环卡 {@value #OPEN_HTTP_IO_THREADS}，http 闭环卡 = 可用核数）。
 *                    RpcClient 没有这个配置，rpc 卡给了就是用法错误
 * @param ioBusyPoll  HTTP 客户端事件循环忙轮询（每圈非阻塞 epoll_wait / selectNow，不睡、不需要被唤醒；每个 IO 线程占满一个核）；
 *                    null = 缺省（http 开环卡开、http 闭环卡关）。同样只对 http 卡有效
 * @param httpClient  http 卡的客户端：{@link #HTTP_CLIENT_NETTY}（事件循环）或 {@link #HTTP_CLIENT_BLOCKING}（每连接一个阻塞
 *                    {@code Socket}，调用方线程自己收发，见 {@link BlockingHttpCallTarget}）；null = 缺省。rpc 卡给了就是用法错误
 * @param pinDispatcher Linux 上把开环调度线程钉到这个核（其余线程钉到进程亲和里剩下的核）；null = 不钉
 * @param preWarmup   第 1 轮之前以同样负载跑的预热段（不计入任何一轮）；null = 卡片的 {@code load.warmup}，{@link Duration#ZERO} = 不跑
 */
record HarnessOptions(String openWorkers, Integer poolSize, String httpClient, Integer ioThreads, Boolean ioBusyPoll,
		Integer pinDispatcher, Duration preWarmup) {

	static final String WORKERS_PER_REQUEST = "per-request";
	static final String WORKERS_POOL = "pool";
	static final String WORKERS_POOL_PLATFORM = "pool-platform";
	static final int DEFAULT_RPC_POOL_SIZE = 64;
	static final String HTTP_CLIENT_NETTY = "netty";
	static final String HTTP_CLIENT_BLOCKING = "blocking";
	/** http 开环卡缺省的 IO 线程数（配忙轮询）：一个忙轮询的事件循环承载交叉验证的 5000 req/s 绰绰有余，只多占一个核。 */
	static final int OPEN_HTTP_IO_THREADS = 1;

	/** 不带任何选项时的行为。 */
	static final HarnessOptions DEFAULTS = new HarnessOptions(null, null, null, null, null, null, null);

	HarnessOptions {
		if (openWorkers != null && !WORKERS_PER_REQUEST.equals(openWorkers) && !WORKERS_POOL.equals(openWorkers)
				&& !WORKERS_POOL_PLATFORM.equals(openWorkers)) {
			throw new IllegalArgumentException("--open-workers must be " + WORKERS_PER_REQUEST + ", " + WORKERS_POOL + "[:N] or "
					+ WORKERS_POOL_PLATFORM + "[:N], got \""
					+ openWorkers + "\"");
		}
		if (httpClient != null && !HTTP_CLIENT_NETTY.equals(httpClient) && !HTTP_CLIENT_BLOCKING.equals(httpClient)) {
			throw new IllegalArgumentException("--http-client must be " + HTTP_CLIENT_NETTY + " or " + HTTP_CLIENT_BLOCKING + ", got \""
					+ httpClient + "\"");
		}
		if (poolSize != null && (poolSize < 1 || poolSize > 100_000)) {
			throw new IllegalArgumentException("--open-workers pool:N needs 1 <= N <= 100000, got " + poolSize);
		}
		if (ioThreads != null && (ioThreads < 1 || ioThreads > 1024)) {
			throw new IllegalArgumentException("--io-threads must be 1..1024, got " + ioThreads);
		}
		if (pinDispatcher != null && pinDispatcher < 0) {
			throw new IllegalArgumentException("--pin-dispatcher must be a CPU number >= 0, got " + pinDispatcher);
		}
		if (preWarmup != null && preWarmup.isNegative()) {
			throw new IllegalArgumentException("--pre-warmup must not be negative");
		}
	}

	/**
	 * 命令行文本 → 选项；null 表示没给（取缺省）。
	 *
	 * @param openWorkers {@code per-request} | {@code pool} | {@code pool:N}
	 * @param pin         CPU 编号，或 {@code off} / {@code none}（不钉）
	 * @param preWarmup   时长（{@code 20s}）或 {@code 0}（关闭）
	 */
	static HarnessOptions parse(String openWorkers, String ioThreads, String pin, String preWarmup) {
		return parse(openWorkers, ioThreads, null, pin, preWarmup);
	}

	static HarnessOptions parse(String openWorkers, String ioThreads, String busyPoll, String pin, String preWarmup) {
		return parse(openWorkers, null, ioThreads, busyPoll, pin, preWarmup);
	}

	/**
	 * @param httpClient {@code netty} | {@code blocking}
	 * @param busyPoll {@code on} | {@code off}
	 */
	static HarnessOptions parse(String openWorkers, String httpClient, String ioThreads, String busyPoll, String pin, String preWarmup) {
		String workers = openWorkers == null ? DEFAULTS.openWorkers() : openWorkers.trim();
		Integer pool = DEFAULTS.poolSize();
		for (String kind : new String[] { WORKERS_POOL_PLATFORM, WORKERS_POOL }) {
			if (workers != null && workers.startsWith(kind + ":")) {
				pool = integer("--open-workers " + kind + ":N", workers.substring(kind.length() + 1));
				workers = kind;
				break;
			}
		}
		Integer io = ioThreads == null ? DEFAULTS.ioThreads() : integer("--io-threads", ioThreads);
		Integer cpu = DEFAULTS.pinDispatcher();
		if (pin != null) {
			String p = pin.trim();
			cpu = p.equals("off") || p.equals("none") ? null : integer("--pin-dispatcher", p);
		}
		Duration pre = DEFAULTS.preWarmup();
		if (preWarmup != null) {
			String w = preWarmup.trim();
			pre = w.equals("0") ? Duration.ZERO : Durations.parse(w);
		}
		Boolean poll = DEFAULTS.ioBusyPoll();
		if (busyPoll != null) {
			poll = switch (busyPoll.trim()) {
				case "on" -> true;
				case "off" -> false;
				default -> throw new IllegalArgumentException("--io-busy-poll must be on or off, got \"" + busyPoll + "\"");
			};
		}
		String client = httpClient == null ? DEFAULTS.httpClient() : httpClient.trim();
		return new HarnessOptions(workers, pool, client, io, poll, cpu, pre);
	}

	private static Integer integer(String option, String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(option + " must be an integer, got \"" + text + "\"");
		}
	}

	/**
	 * 开环实际的执行者：给了就用；否则 http 卡用阻塞客户端时是 {@code pool-platform}（阻塞 read 由内核直接唤醒平台线程，
	 * 虚拟线程要多经 JDK 的 poller 与载体调度两跳），其余（rpc 卡、netty 客户端）是 {@code pool}。
	 */
	String effectiveOpenWorkers(Profile profile) {
		if (openWorkers != null) {
			return openWorkers;
		}
		return profile.target().http() && HTTP_CLIENT_BLOCKING.equals(effectiveHttpClient(profile)) ? WORKERS_POOL_PLATFORM : WORKERS_POOL;
	}

	boolean pool(Profile profile) {
		String workers = effectiveOpenWorkers(profile);
		return WORKERS_POOL.equals(workers) || WORKERS_POOL_PLATFORM.equals(workers);
	}

	/** 工作者池用平台线程（否则虚拟线程）。 */
	boolean platformWorkers(Profile profile) {
		return WORKERS_POOL_PLATFORM.equals(effectiveOpenWorkers(profile));
	}

	/** pool 的实际工作者数。 */
	int effectivePoolSize(Profile profile) {
		if (poolSize != null) {
			return poolSize;
		}
		return profile.target().http() ? profile.target().httpConnections() : DEFAULT_RPC_POOL_SIZE;
	}

	/** 给了任何只对 http 有效的选项（rpc 卡给了就是用法错误）。 */
	boolean httpIoOptionsGiven() {
		return httpClient != null || ioThreads != null || ioBusyPoll != null;
	}

	/**
	 * http 卡实际用的客户端：给了就用；否则开环卡 {@code blocking}（交叉验证的正式卡上比 netty 客户端稳，见探针），
	 * 闭环卡 {@code netty}（闭环卡标定的是 harness 的发压容量，保持事件循环客户端）。
	 */
	String effectiveHttpClient(Profile profile) {
		if (httpClient != null) {
			return httpClient;
		}
		return profile.load().closed() ? HTTP_CLIENT_NETTY : HTTP_CLIENT_BLOCKING;
	}

	/** HTTP 客户端实际的 IO 线程数：给了就用；否则开环卡 {@value #OPEN_HTTP_IO_THREADS}、闭环卡 = 可用核数（闭环卡标定的是发压容量）。 */
	int effectiveIoThreads(Profile profile) {
		if (ioThreads != null) {
			return ioThreads;
		}
		return profile.load().closed() ? Runtime.getRuntime().availableProcessors() : OPEN_HTTP_IO_THREADS;
	}

	/** HTTP 客户端事件循环是否忙轮询：给了就用；否则开环卡开、闭环卡关。 */
	boolean effectiveIoBusyPoll(Profile profile) {
		return ioBusyPoll != null ? ioBusyPoll : !profile.load().closed();
	}

	/** 实际的预热段时长（null = 卡片的 warmup）。 */
	Duration effectivePreWarmup(Profile profile) {
		return preWarmup == null ? profile.load().warmup() : preWarmup;
	}

	/** 进 JSON 的 {@code run.harness_options}。 */
	Map<String, Object> toJson(Profile profile) {
		Map<String, Object> json = new LinkedHashMap<>();
		boolean open = !profile.load().closed();
		json.put("open_workers", open ? effectiveOpenWorkers(profile) : "n/a (closed loop)");
		json.put("open_pool_size", open && pool(profile) ? effectivePoolSize(profile) : null);
		boolean http = profile.target().http();
		json.put("http_client", http ? effectiveHttpClient(profile) : "n/a (rpc)");
		boolean blocking = http && HTTP_CLIENT_BLOCKING.equals(effectiveHttpClient(profile));
		json.put("io_threads", !http ? "n/a (rpc: RpcClient's own event loops)" : blocking ? "n/a (blocking client)" : effectiveIoThreads(profile));
		json.put("io_busy_poll", !http ? "n/a (rpc)" : blocking ? "n/a (blocking client)" : effectiveIoBusyPoll(profile));
		json.put("io_source", httpClient == null && ioThreads == null && ioBusyPoll == null ? "default" : "command line");
		json.put("workers_source", openWorkers == null ? "default" : "command line");
		json.put("pin_dispatcher", pinDispatcher == null ? "off" : pinDispatcher);
		Duration pre = effectivePreWarmup(profile);
		json.put("pre_warmup", Durations.format(pre));
		json.put("pre_warmup_ms", Durations.millis(pre));
		json.put("pre_warmup_source", preWarmup == null ? "load.warmup" : "--pre-warmup");
		return json;
	}
}
