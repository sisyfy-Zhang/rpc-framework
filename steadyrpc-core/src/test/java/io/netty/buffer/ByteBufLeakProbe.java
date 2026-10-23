package io.netty.buffer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.util.ResourceLeakDetector;

/**
 * 测试用的 ByteBuf 泄漏探针：在一段代码期间把检测级别提到 PARANOID（每个缓冲区都跟踪），并在 Netty 的
 * ByteBuf 泄漏检测器上挂一个 {@link ResourceLeakDetector.LeakListener}，测试结束前反复 GC 把被回收但没 release
 * 的缓冲区"逼"出来，断言一条都没有。
 *
 * <p>为什么放在 {@code io.netty.buffer} 包里：ByteBuf 的检测器是 {@code AbstractByteBuf.leakDetector}（包内可见的
 * static final），只有同包代码能给它挂监听器。考虑过的另外两种做法：
 * <ul>
 *   <li>{@code ResourceLeakDetectorFactory.setResourceLeakDetectorFactory} 注入自定义检测器：检测器在
 *       {@code AbstractByteBuf} 类初始化时就创建好了，surefire 复用 fork 时早已加载，注入不进去——不可靠。</li>
 *   <li>grep 构建日志里的 "LEAK:"（CI 的 paranoid 作业现在的做法）：能兜底，但报告发生在<b>之后某次分配</b>时，
 *       报告所在线程与测试都不是泄漏发生处；同一份调用栈只报一次；也定位不到是哪个测试。</li>
 * </ul>
 * 这里的探针在具体测试里同步断言，失败信息带完整的访问记录。局限：检测器对完全相同的记录只报告一次（进程级去重），
 * 所以探针只适合断言"这个场景不产生新泄漏"；它不依赖 {@code -Dio.netty.leakDetection.level}。
 */
public final class ByteBufLeakProbe implements AutoCloseable {

	private final ResourceLeakDetector.Level previousLevel;
	private final List<String> leaks = new CopyOnWriteArrayList<>();

	private ByteBufLeakProbe() {
		previousLevel = ResourceLeakDetector.getLevel();
		// 先把开探针之前就已经待报告的泄漏排掉，避免算到本测试头上
		AbstractByteBuf.leakDetector.setLeakListener(null);
		drain(Duration.ofMillis(200));
		ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
		AbstractByteBuf.leakDetector.setLeakListener((type, records) -> leaks.add(records));
	}

	public static ByteBufLeakProbe start() {
		return new ByteBufLeakProbe();
	}

	/** 反复 GC 并触发检测器的队列处理，最多 {@code within}；返回这期间报告的泄漏（访问记录）。 */
	public List<String> collect(Duration within) {
		drain(within);
		return new ArrayList<>(leaks);
	}

	/** 断言没有泄漏：GC 约 1 秒，期间任何泄漏报告都让断言失败，失败信息带第一条泄漏的访问记录。 */
	public void assertNoLeaks() {
		List<String> found = collect(Duration.ofSeconds(1));
		if (!found.isEmpty()) {
			throw new AssertionError(found.size() + " ByteBuf leak(s) detected; first:" + found.get(0));
		}
	}

	private static void drain(Duration within) {
		long deadline = System.nanoTime() + within.toNanos();
		do {
			System.gc();
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			// 检测器只在跟踪新缓冲区时处理引用队列（报告泄漏）：分配几个跟踪中的缓冲区把队列推一推
			for (int i = 0; i < 4; i++) {
				PooledByteBufAllocator.DEFAULT.directBuffer(1).release();
			}
		} while (System.nanoTime() - deadline < 0);
	}

	@Override
	public void close() {
		AbstractByteBuf.leakDetector.setLeakListener(null);
		ResourceLeakDetector.setLevel(previousLevel);
	}
}
