package io.github.sisyfyzhang.steadyrpc.core.codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.sisyfyzhang.steadyrpc.core.protocol.MessageType;
import io.github.sisyfyzhang.steadyrpc.core.protocol.ProtocolException;
import io.github.sisyfyzhang.steadyrpc.core.protocol.RpcMessage;
import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * jqwik 属性测试：AttachmentsCodec / EnvelopeCodec 的随机往返，以及随机（或随机篡改的合法）字节输入时
 * 只抛 {@link ProtocolException} / {@link DecoderException}、不泄漏 ByteBuf。
 *
 * <p>泄漏判定直接看引用计数：纯函数编解码器不得改变输入缓冲区的 refCnt；经 pipeline 解码后，输入缓冲区与所有产出的
 * 消息在释放后 refCnt 必须为 0。比等 GC 触发的 ResourceLeakDetector 报告更确定。
 */
class CodecFuzzTest {

	private static final int LIMIT = 64 * 1024;

	// ------------------------------------------------------------------ 生成器

	/** UTF-8 可往返的字符串：BMP 中除代理区以外的字符（代理对另有样例测试覆盖）。 */
	private static Arbitrary<String> text(int maxLength) {
		return Arbitraries.strings().withCharRange('\u0000', '퟿').withCharRange('', '�').ofMaxLength(maxLength);
	}

	@Provide
	Arbitrary<Map<String, String>> attachments() {
		return Arbitraries.maps(text(40), text(80)).ofMaxSize(20);
	}

	@Provide
	Arbitrary<List<byte[]>> args() {
		Arbitrary<byte[]> bytes = Arbitraries.bytes().array(byte[].class).ofMaxSize(64);
		return bytes.injectNull(0.2).list().ofMaxSize(12);
	}

	@Provide
	Arbitrary<byte[]> nullableBytes() {
		return Arbitraries.bytes().array(byte[].class).ofMaxSize(128).injectNull(0.3);
	}

	@Provide
	Arbitrary<String> fields() {
		return text(60);
	}

	@Provide
	Arbitrary<byte[]> garbage() {
		return Arbitraries.bytes().array(byte[].class).ofMaxSize(256);
	}

	/** 合法帧随机篡改若干字节：比纯随机字节更能走到解码器深处的分支。 */
	@Provide
	Arbitrary<byte[]> mutatedFrames() {
		Arbitrary<byte[]> valid = Combinators.combine(attachments(), args(), fields())
				.as((att, a, method) -> {
					ByteBuf body = Unpooled.buffer();
					EnvelopeCodec.writeRequest(body, "com.x.Svc", "", "default", method, "int", a);
					ByteBuf frame = RpcCodecTest.encode(RpcMessage.request(1, (byte) 1, att, body));
					byte[] bytes = ByteBufUtil.getBytes(frame);
					frame.release();
					return bytes;
				});
		Arbitrary<int[]> mutations = Arbitraries.integers().between(0, Integer.MAX_VALUE).array(int[].class).ofMinSize(1).ofMaxSize(6);
		return Combinators.combine(valid, mutations).as((bytes, positions) -> {
			byte[] copy = bytes.clone();
			for (int p : positions) {
				int index = p % copy.length;
				copy[index] = (byte) (copy[index] ^ (1 + (p >>> 8) % 255));
			}
			return copy;
		});
	}

	// ------------------------------------------------------------------ 往返

	@Property(tries = 1000)
	void attachmentsRoundTrip(@ForAll("attachments") Map<String, String> attachments) {
		ByteBuf buf = Unpooled.buffer();
		try {
			int written = AttachmentsCodec.encode(attachments, buf, LIMIT);
			assertEquals(written, buf.readableBytes());
			assertEquals(AttachmentsCodec.encodedLength(attachments, LIMIT), written);
			assertEquals(attachments, AttachmentsCodec.decode(buf, LIMIT));
			assertEquals(1, buf.refCnt());
		} finally {
			buf.release();
		}
	}

	@Property(tries = 1000)
	void requestEnvelopeRoundTrip(@ForAll("fields") String service, @ForAll("fields") String version,
			@ForAll("fields") String group, @ForAll("fields") String method, @ForAll("fields") String paramSig,
			@ForAll("args") List<byte[]> args) {
		ByteBuf buf = Unpooled.buffer();
		try {
			EnvelopeCodec.writeRequest(buf, service, version, group, method, paramSig, args);
			EnvelopeCodec.RequestEnvelope decoded = EnvelopeCodec.readRequest(buf);
			assertEquals(List.of(service, version, group, method, paramSig),
					List.of(decoded.service(), decoded.version(), decoded.group(), decoded.method(), decoded.paramSig()));
			assertEquals(args.size(), decoded.args().size());
			for (int i = 0; i < args.size(); i++) {
				if (args.get(i) == null) {
					assertNull(decoded.args().get(i));
				} else {
					assertArrayEquals(args.get(i), ByteBufUtil.getBytes(decoded.args().get(i)));
				}
			}
			assertEquals(1, buf.refCnt());
		} finally {
			buf.release();
		}
	}

	@Property(tries = 1000)
	void valueRoundTrip(@ForAll("nullableBytes") byte[] value) {
		ByteBuf buf = Unpooled.buffer();
		try {
			EnvelopeCodec.writeValue(buf, value);
			ByteBuf decoded = EnvelopeCodec.readValue(buf);
			if (value == null) {
				assertNull(decoded);
			} else {
				assertArrayEquals(value, ByteBufUtil.getBytes(decoded));
			}
		} finally {
			buf.release();
		}
	}

	@Property(tries = 1000)
	void errorEnvelopeRoundTrip(@ForAll("fields") String className, @ForAll("fields") String message,
			@ForAll("fields") String stack) {
		ByteBuf buf = Unpooled.buffer();
		try {
			EnvelopeCodec.writeError(buf, className, message, stack);
			EnvelopeCodec.ErrorEnvelope decoded = EnvelopeCodec.readError(buf);
			assertEquals(List.of(className, message, stack), List.of(decoded.className(), decoded.message(), decoded.remoteStack()));
		} finally {
			buf.release();
		}
	}

	// ------------------------------------------------------------------ 随机字节

	@Property(tries = 2000)
	void randomBytesIntoPureCodecsOnlyThrowProtocolException(@ForAll("garbage") byte[] bytes) {
		ByteBuf buf = Unpooled.wrappedBuffer(bytes);
		try {
			expectOnlyProtocolException(() -> AttachmentsCodec.decode(buf, LIMIT));
			expectOnlyProtocolException(() -> EnvelopeCodec.readRequest(buf));
			expectOnlyProtocolException(() -> EnvelopeCodec.readValue(buf));
			expectOnlyProtocolException(() -> EnvelopeCodec.readError(buf));
			assertEquals(0, buf.readerIndex(), "pure codecs must not move the caller's reader index");
			assertEquals(1, buf.refCnt(), "pure codecs must not retain or release the input");
		} finally {
			buf.release();
		}
	}

	@Property(tries = 2000)
	void randomBytesIntoPipelineOnlyThrowDecoderExceptionAndNeverLeak(@ForAll("garbage") byte[] bytes) {
		feedPipeline(bytes);
	}

	@Property(tries = 1000)
	void mutatedFramesIntoPipelineOnlyThrowDecoderExceptionAndNeverLeak(@ForAll("mutatedFrames") byte[] bytes) {
		feedPipeline(bytes);
	}

	@Property(tries = 300)
	void sizeLimitIsEnforcedOnEncode(@ForAll @Size(min = 1, max = 5) List<@net.jqwik.api.constraints.StringLength(min = 30, max = 60) String> values) {
		Map<String, String> attachments = new java.util.LinkedHashMap<>();
		for (int i = 0; i < values.size(); i++) {
			attachments.put("k" + i, values.get(i));
		}
		int limit = 20;
		ByteBuf buf = Unpooled.buffer();
		try {
			AttachmentsCodec.encode(attachments, buf, limit);
			fail("expected IllegalArgumentException for attachments over " + limit + " bytes");
		} catch (IllegalArgumentException expected) {
			assertEquals(0, buf.readableBytes(), "nothing is written when validation fails");
		} finally {
			buf.release();
		}
	}

	private static void feedPipeline(byte[] bytes) {
		EmbeddedChannel channel = new EmbeddedChannel(new RpcFrameDecoder(), new RpcMessageDecoder());
		ByteBuf input = Unpooled.wrappedBuffer(bytes);
		List<RpcMessage> decoded = new ArrayList<>();
		try {
			channel.writeInbound(input);
		} catch (DecoderException expected) {
			// 唯一允许的失败形态（ProtocolException、TooLongFrameException 都是它的子类）
		} catch (RuntimeException other) {
			fail("unexpected " + other + " for input " + ByteBufUtil.hexDump(bytes));
		}
		Object msg;
		while ((msg = channel.readInbound()) != null) {
			RpcMessage message = (RpcMessage) msg;
			// 解出来的消息字段必须自洽
			if (message.messageType() == null || message.status() == null || message.attachments() == null) {
				fail("inconsistent message " + message);
			}
			decoded.add(message);
		}
		// 同一输入可能切出多帧，各帧 body 是同一父缓冲区的 retained slice（slice 的 refCnt 反映的是父缓冲区），
		// 所以逐条看 refCnt 没有意义；全部释放后看输入缓冲区本身是否归零才是确定的泄漏判据
		decoded.forEach(RpcMessage::release);
		channel.finishAndReleaseAll();
		if (bytes.length > 0) { // wrappedBuffer(byte[0]) 同样是 EMPTY_BUFFER
			assertEquals(0, input.refCnt(), "input buffer leaked for " + ByteBufUtil.hexDump(bytes));
		}
	}

	private static void expectOnlyProtocolException(Runnable action) {
		try {
			action.run();
		} catch (ProtocolException expected) {
			// ok
		} catch (RuntimeException other) {
			fail("expected only ProtocolException, got " + other);
		}
	}

	/** 保证生成器真的产出了能被完整解码的帧（否则上面的属性只测到了拒绝路径）。 */
	@Property(tries = 50)
	void unmutatedFramesDecode(@ForAll("attachments") Map<String, String> attachments, @ForAll("args") List<byte[]> args) {
		ByteBuf body = Unpooled.buffer();
		EnvelopeCodec.writeRequest(body, "com.x.Svc", "", "default", "m", "", args);
		ByteBuf frame = RpcCodecTest.encode(RpcMessage.request(5, (byte) 1, attachments, body));
		EmbeddedChannel channel = new EmbeddedChannel(new RpcFrameDecoder(), new RpcMessageDecoder());
		channel.writeInbound(frame);
		RpcMessage message = channel.readInbound();
		try {
			assertEquals(MessageType.REQUEST, message.messageType());
			assertEquals(Status.OK, message.status());
			assertEquals(attachments, message.attachments());
			assertEquals(args.size(), EnvelopeCodec.readRequest(message.body()).args().size());
		} finally {
			message.release();
		}
		assertEquals(0, frame.refCnt());
	}

	/** 代理对（emoji）与超长字段截断：随机生成器刻意不覆盖的两类输入。 */
	@net.jqwik.api.Example
	void surrogatePairsRoundTripAndLongErrorFieldsAreTruncated() {
		ByteBuf buf = Unpooled.buffer();
		try {
			AttachmentsCodec.encode(Map.of("emoji", "😀 订单"), buf, LIMIT);
			assertEquals(Map.of("emoji", "😀 订单"), AttachmentsCodec.decode(buf, LIMIT));
		} finally {
			buf.release();
		}
		String longStack = "😀".repeat(20_000); // 80000 字节
		ByteBuf err = Unpooled.buffer();
		try {
			EnvelopeCodec.writeError(err, "X", "m", longStack);
			String decoded = EnvelopeCodec.readError(err).remoteStack();
			assertEquals(16_383 * 2, decoded.length(), "truncated at a code point boundary (65532 bytes)");
			assertEquals(longStack.substring(0, decoded.length()), decoded);
			assertEquals(65532, decoded.getBytes(StandardCharsets.UTF_8).length);
		} finally {
			err.release();
		}
	}
}
