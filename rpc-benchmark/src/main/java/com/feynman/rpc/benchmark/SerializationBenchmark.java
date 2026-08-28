package com.feynman.rpc.benchmark;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.alibaba.fastjson2.JSON;
import com.feynman.rpc.serialize.fastjson2.Fastjson2Serializer;
import com.feynman.rpc.serialize.protobuf.ProtobufSerializer;

/**
 * 对比三种序列化 RPC 参数形态 POJO（{@link SampleUser}）的方式：
 * <ul>
 *   <li>{@code fastjson2Text} —— fastjson2 生成/解析 String，对应按行分帧的文本协议
 *       会用到的做法，作为文本协议的性能对照组。</li>
 *   <li>{@code fastjson2Binary} —— 默认的 {@link Fastjson2Serializer}（fastjson2 直接输出
 *       {@code byte[]}，不经过 String 中转）。</li>
 *   <li>{@code protobuf} —— {@link ProtobufSerializer}（Protostuff 运行时 schema）。</li>
 * </ul>
 * 运行方式：{@code mvn -pl rpc-benchmark package && java -jar rpc-benchmark/target/benchmarks.jar}。
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SerializationBenchmark {

	private SampleUser sample;
	private Fastjson2Serializer fastjson2Serializer;
	private ProtobufSerializer protobufSerializer;

	private String fastjson2TextEncoded;
	private byte[] fastjson2BinaryEncoded;
	private byte[] protobufEncoded;

	@Setup
	public void setup() {
		sample = SampleUser.sample();
		fastjson2Serializer = new Fastjson2Serializer();
		protobufSerializer = new ProtobufSerializer();

		fastjson2TextEncoded = JSON.toJSONString(sample);
		fastjson2BinaryEncoded = fastjson2Serializer.serialize(sample);
		protobufEncoded = protobufSerializer.serialize(sample);
	}

	@Benchmark
	public String fastjson2Text_serialize() {
		return JSON.toJSONString(sample);
	}

	@Benchmark
	public SampleUser fastjson2Text_deserialize() {
		return JSON.parseObject(fastjson2TextEncoded, SampleUser.class);
	}

	@Benchmark
	public byte[] fastjson2Binary_serialize() {
		return fastjson2Serializer.serialize(sample);
	}

	@Benchmark
	public SampleUser fastjson2Binary_deserialize() {
		return fastjson2Serializer.deserialize(fastjson2BinaryEncoded, SampleUser.class);
	}

	@Benchmark
	public byte[] protobuf_serialize() {
		return protobufSerializer.serialize(sample);
	}

	@Benchmark
	public SampleUser protobuf_deserialize() {
		return protobufSerializer.deserialize(protobufEncoded, SampleUser.class);
	}
}
