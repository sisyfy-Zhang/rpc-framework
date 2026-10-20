package com.feynman.rpc.core.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.flush.FlushConsolidationHandler;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConnectionWriteQueue}：FIFO、批末单次 flush、每 {@link ConnectionWriteQueue#FLUSH_EVERY} 条的中途 flush、
 * 排队期间失效的条目不写出、事件循环已关时逐条通知、多生产者并发下不丢任务（scheduled 的 CAS 交接）。
 *
 * <p>用 Netty 的 local transport：真实的事件循环线程（{@link io.netty.channel.embedded.EmbeddedChannel} 的事件循环对任何线程都
 * "inEventLoop"，测不到入队路径）。客户端 pipeline 最靠近 transport 处是一个计数 write / flush 的出站处理器。
 */
class ConnectionWriteQueueTest {

	private EventLoopGroup group;
	private Channel serverChannel;
	private Channel channel;
	private final List<Object> received = Collections.synchronizedList(new ArrayList<>());
	private final List<Object> writes = Collections.synchronizedList(new ArrayList<>());
	private final AtomicInteger flushes = new AtomicInteger();

	@BeforeEach
	void connect() throws Exception {
		connect(false);
	}

	private void connect(boolean flushConsolidation) throws Exception {
		group = new DefaultEventLoopGroup(1);
		LocalAddress address = new LocalAddress("cwq-" + System.nanoTime());
		serverChannel = new ServerBootstrap().group(group).channel(LocalServerChannel.class)
				.childHandler(new ChannelInitializer<LocalChannel>() {
					@Override
					protected void initChannel(LocalChannel ch) {
						ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
							@Override
							public void channelRead(ChannelHandlerContext ctx, Object msg) {
								received.add(msg);
							}
						});
					}
				}).bind(address).sync().channel();
		channel = new Bootstrap().group(group).channel(LocalChannel.class)
				.handler(new ChannelInitializer<LocalChannel>() {
					@Override
					protected void initChannel(LocalChannel ch) {
						ch.pipeline().addFirst("counter", new ChannelOutboundHandlerAdapter() {
							@Override
							public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
								writes.add(msg);
								ctx.write(msg, promise);
							}

							@Override
							public void flush(ChannelHandlerContext ctx) {
								flushes.incrementAndGet();
								ctx.flush();
							}
						});
						if (flushConsolidation) {
							ch.pipeline().addLast(FlushConsolidation.HANDLER_NAME,
									new FlushConsolidationHandler(FlushConsolidation.EXPLICIT_FLUSH_AFTER_FLUSHES, true));
						}
					}
				}).connect(address).sync().channel();
	}

	@AfterEach
	void close() {
		if (channel != null) {
			channel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
		}
		if (serverChannel != null) {
			serverChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
		}
		group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
	}

	/** 测试条目：记录各回调。 */
	static final class TestEntry extends ConnectionWriteQueue.Entry {
		final boolean write;
		volatile boolean beforeWriteCalled;
		volatile ChannelFuture writtenFuture;
		volatile RejectedExecutionException rejected;
		volatile Thread rejectedOn;

		TestEntry(Channel channel, Object message, boolean write) {
			super(channel, message);
			this.write = write;
		}

		TestEntry(Channel channel, Object message) {
			this(channel, message, true);
		}

		@Override
		protected boolean beforeWrite(Channel channel) {
			beforeWriteCalled = true;
			return write;
		}

		@Override
		protected void written(ChannelFuture future) {
			writtenFuture = future;
		}

		@Override
		protected void notWritten(RejectedExecutionException cause) {
			rejected = cause;
			rejectedOn = Thread.currentThread();
		}
	}

	/** 在事件循环上占住一个任务，期间生产者入队的条目都会落进同一批。 */
	private CountDownLatch blockEventLoop() throws InterruptedException {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch blocked = new CountDownLatch(1);
		channel.eventLoop().execute(() -> {
			blocked.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(blocked.await(5, TimeUnit.SECONDS));
		return release;
	}

	@Test
	void entriesQueuedOffTheEventLoopAreWrittenInOrderWithOneFlushPerBatch() throws Exception {
		ConnectionWriteQueue queue = new ConnectionWriteQueue(channel);
		assertFalse(queue.inEventLoop());
		int flushesBefore = flushes.get();
		CountDownLatch release = blockEventLoop();
		List<TestEntry> entries = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			TestEntry entry = new TestEntry(channel, "m" + i);
			entries.add(entry);
			queue.enqueue(entry);
		}
		assertEquals(100, queue.size(), "nothing is written while the event loop is busy");
		release.countDown();
		await().atMost(5, TimeUnit.SECONDS).until(() -> received.size() == 100);
		List<Object> expected = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			expected.add("m" + i);
		}
		assertEquals(expected, List.copyOf(writes), "FIFO");
		assertEquals(expected, List.copyOf(received));
		assertEquals(1, flushes.get() - flushesBefore, "one flush for the whole batch");
		await().atMost(5, TimeUnit.SECONDS).until(() -> entries.stream().allMatch(e -> e.writtenFuture != null));
		assertTrue(entries.stream().allMatch(e -> e.writtenFuture.isSuccess()));
		assertEquals(0, queue.size());
	}

	@Test
	void aLargeBatchIsFlushedEveryFlushEveryEntries() throws Exception {
		ConnectionWriteQueue queue = new ConnectionWriteQueue(channel);
		int n = ConnectionWriteQueue.FLUSH_EVERY * 2 + 44;
		int flushesBefore = flushes.get();
		CountDownLatch release = blockEventLoop();
		for (int i = 0; i < n; i++) {
			queue.enqueue(new TestEntry(channel, i));
		}
		release.countDown();
		await().atMost(5, TimeUnit.SECONDS).until(() -> received.size() == n);
		assertEquals(3, flushes.get() - flushesBefore, "flush after 128, after 256, and at the end of the batch");
	}

	@Test
	void moreThanMaxPerDrainIsWrittenAcrossSeveralDrainTasks() throws Exception {
		ConnectionWriteQueue queue = new ConnectionWriteQueue(channel);
		int n = ConnectionWriteQueue.MAX_PER_DRAIN * 2 + 7;
		CountDownLatch release = blockEventLoop();
		for (int i = 0; i < n; i++) {
			queue.enqueue(new TestEntry(channel, i));
		}
		release.countDown();
		await().atMost(10, TimeUnit.SECONDS).until(() -> received.size() == n);
		for (int i = 0; i < n; i++) {
			assertEquals(i, writes.get(i), "FIFO across drain tasks");
		}
		assertEquals(0, queue.size());
	}

	@Test
	void entriesThatDeclineInBeforeWriteAreNotWrittenAndNotFlushed() throws Exception {
		ConnectionWriteQueue queue = new ConnectionWriteQueue(channel);
		int flushesBefore = flushes.get();
		CountDownLatch release = blockEventLoop();
		TestEntry skipped = new TestEntry(channel, "skip", false);
		TestEntry kept = new TestEntry(channel, "keep");
		queue.enqueue(skipped);
		queue.enqueue(kept);
		release.countDown();
		await().atMost(5, TimeUnit.SECONDS).until(() -> kept.writtenFuture != null);
		assertTrue(skipped.beforeWriteCalled);
		assertEquals(null, skipped.writtenFuture, "a declined entry is never handed to the pipeline");
		assertEquals(List.of("keep"), List.copyOf(writes));
		assertEquals(1, flushes.get() - flushesBefore);

		// 整批都被拒绝时不 flush
		int before = flushes.get();
		CountDownLatch again = blockEventLoop();
		TestEntry onlySkipped = new TestEntry(channel, "skip2", false);
		queue.enqueue(onlySkipped);
		again.countDown();
		await().atMost(5, TimeUnit.SECONDS).until(() -> onlySkipped.beforeWriteCalled);
		channel.eventLoop().submit(() -> { }).sync();
		assertEquals(before, flushes.get());
	}

	@Test
	void aBeforeWriteThatThrowsFailsThatEntryOnly() throws Exception {
		ConnectionWriteQueue queue = new ConnectionWriteQueue(channel);
		CountDownLatch release = blockEventLoop();
		AtomicInteger failures = new AtomicInteger();
		ConnectionWriteQueue.Entry throwing = new ConnectionWriteQueue.Entry(channel, "boom") {
			@Override
			protected boolean beforeWrite(Channel ch) {
				throw new IllegalStateException("boom");
			}

			@Override
			protected void written(ChannelFuture future) {
				if (!future.isSuccess()) {
					failures.incrementAndGet();
				}
			}

			@Override
			protected void notWritten(RejectedExecutionException cause) {
			}
		};
		TestEntry after = new TestEntry(channel, "after");
		queue.enqueue(throwing);
		queue.enqueue(after);
		release.countDown();
		await().atMost(5, TimeUnit.SECONDS).until(() -> after.writtenFuture != null);
		assertEquals(1, failures.get());
		assertEquals(List.of("after"), List.copyOf(received));
	}

	@Test
	void onTheEventLoopEnqueueWritesImmediately() throws Exception {
		ConnectionWriteQueue queue = new ConnectionWriteQueue(channel);
		TestEntry entry = new TestEntry(channel, "now");
		channel.eventLoop().submit(() -> {
			assertTrue(queue.inEventLoop());
			queue.enqueue(entry);
			assertEquals(0, queue.size(), "not queued");
			assertEquals(List.of("now"), List.copyOf(writes), "written synchronously");
		}).sync();
		await().atMost(5, TimeUnit.SECONDS).until(() -> received.size() == 1);
	}

	@Test
	void whenTheEventLoopIsShutDownEveryQueuedEntryIsReportedNotWritten() throws Exception {
		ConnectionWriteQueue queue = new ConnectionWriteQueue(channel);
		channel.close().sync();
		serverChannel.close().sync();
		group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
		List<TestEntry> entries = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			TestEntry entry = new TestEntry(channel, i);
			entries.add(entry);
			queue.enqueue(entry);
		}
		for (TestEntry entry : entries) {
			assertTrue(entry.rejected != null, "notWritten is called");
			assertEquals(Thread.currentThread(), entry.rejectedOn, "on the producer whose schedule was rejected");
			assertEquals(null, entry.writtenFuture);
		}
		assertEquals(0, queue.size());
	}

	@Test
	void withFlushConsolidationTheBatchIsStillFlushedOnce() throws Exception {
		close();
		writes.clear();
		received.clear();
		flushes.set(0);
		connect(true);
		ConnectionWriteQueue queue = new ConnectionWriteQueue(channel);
		CountDownLatch release = blockEventLoop();
		for (int i = 0; i < 50; i++) {
			queue.enqueue(new TestEntry(channel, i));
		}
		AtomicInteger flushesWhenProbeRan = new AtomicInteger(-1);
		// 紧跟在 drain 之后的任务：drain 自己直接冲刷（不经 FlushConsolidationHandler 再提交一个 flush 任务，那个任务会排在本任务之后），
		// 所以本任务运行时那一次 flush 已经发生
		channel.eventLoop().execute(() -> flushesWhenProbeRan.set(flushes.get()));
		release.countDown();
		await().atMost(5, TimeUnit.SECONDS).until(() -> received.size() == 50 && flushesWhenProbeRan.get() >= 0);
		assertEquals(1, flushesWhenProbeRan.get(), "the drain task flushed the batch itself, below FlushConsolidationHandler");
		assertEquals(1, flushes.get(), "exactly one real flush reached the transport");
	}

	/** 多生产者并发入队：每条恰好写出一次（scheduled 的 CAS 交接不丢任务、不重复消费）。 */
	@RepeatedTest(5)
	void concurrentProducersNeverLoseOrDuplicateEntries() throws Exception {
		ConnectionWriteQueue queue = new ConnectionWriteQueue(channel);
		int producers = 8;
		int perProducer = 5000;
		Set<String> written = ConcurrentHashMap.newKeySet();
		AtomicInteger duplicates = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		List<Thread> threads = new ArrayList<>();
		for (int p = 0; p < producers; p++) {
			int id = p;
			Thread t = new Thread(() -> {
				try {
					start.await();
				} catch (InterruptedException e) {
					return;
				}
				for (int i = 0; i < perProducer; i++) {
					String message = id + ":" + i;
					queue.enqueue(new ConnectionWriteQueue.Entry(channel, message) {
						@Override
						protected boolean beforeWrite(Channel ch) {
							if (!written.add(message)) {
								duplicates.incrementAndGet();
							}
							return true;
						}

						@Override
						protected void written(ChannelFuture future) {
						}

						@Override
						protected void notWritten(RejectedExecutionException cause) {
						}
					});
					if ((i & 255) == 0) {
						Thread.yield();
					}
				}
			});
			threads.add(t);
			t.start();
		}
		start.countDown();
		for (Thread t : threads) {
			t.join(30_000);
		}
		int total = producers * perProducer;
		await().atMost(20, TimeUnit.SECONDS).until(() -> received.size() == total);
		assertEquals(total, written.size());
		assertEquals(0, duplicates.get());
		assertEquals(0, queue.size());
		// 每个生产者内部的顺序保持（MPSC：同一生产者的入队顺序即出队顺序）
		int[] last = new int[producers];
		java.util.Arrays.fill(last, -1);
		for (Object o : List.copyOf(writes)) {
			String[] parts = ((String) o).split(":");
			int p = Integer.parseInt(parts[0]);
			int i = Integer.parseInt(parts[1]);
			assertTrue(i > last[p], "per-producer FIFO");
			last[p] = i;
		}
	}

	@Test
	void aDuplexHandlerSeesEachQueuedWriteWithItsOwnPromise() throws Exception {
		// 条目自身就是 promise：管道里的处理器拿到的 promise 正是条目对象（不另配 promise / 监听器）
		List<ChannelPromise> promises = Collections.synchronizedList(new ArrayList<>());
		channel.pipeline().addLast(new ChannelDuplexHandler() {
			@Override
			public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
				promises.add(promise);
				ctx.write(msg, promise);
			}
		});
		ConnectionWriteQueue queue = new ConnectionWriteQueue(channel);
		TestEntry entry = new TestEntry(channel, "p");
		queue.enqueue(entry);
		await().atMost(5, TimeUnit.SECONDS).until(() -> entry.writtenFuture != null);
		assertEquals(1, promises.size());
		assertTrue(promises.get(0) == entry);
		assertTrue(entry.writtenFuture == entry);
	}
}
