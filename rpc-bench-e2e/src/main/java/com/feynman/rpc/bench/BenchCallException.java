package com.feynman.rpc.bench;

/**
 * 一次调用的失败，带着报告里的错误分桶名（{@code errors_by_type} 的键）。对比对象的 {@link CallTarget} 用它把自己框架的错误
 * 映射到与本框架可比的桶：超时一律 {@code timeout}（{@link #timeout}），其余用带前缀的状态（例如 gRPC 的
 * {@code grpc_status:UNAVAILABLE}）。不填堆栈：压测循环里每次失败都会构造一个。
 */
public class BenchCallException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String bucket;

	public BenchCallException(String bucket, String message, Throwable cause) {
		super(message, cause, false, false);
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalArgumentException("bucket must not be blank");
		}
		this.bucket = bucket;
	}

	/** 错误分桶名。 */
	public String bucket() {
		return bucket;
	}

	/** 超时（与 rpc 卡的 {@code RpcDeadlineExceededException} 同一个桶）。 */
	public static BenchCallException timeout(String message, Throwable cause) {
		return new BenchCallException(ErrorClassifier.TIMEOUT, message, cause);
	}

	/** 回显内容与发出的不一致：调用"成功"了但结果是错的；开压前的校验遇到它直接中止。 */
	public static BenchCallException payloadMismatch(String message) {
		return new ErrorClassifier.PayloadMismatchException(message);
	}
}
