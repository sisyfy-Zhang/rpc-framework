package com.feynman.rpc.bench;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.config.ReferenceOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 命名刻意避开 *ProbeTest：根 pom 的 surefire 把 *ProbeTest 当探针类默认排除。 */
class EnvironmentCollectionTest {

	private static boolean linux() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
	}

	@Test
	void cpusAllowedIsAlwaysPresent() {
		Map<String, Object> env = EnvironmentProbe.collect();
		assertTrue(env.containsKey("cpus_allowed"), env.toString());
		if (!linux()) {
			assertEquals(EnvironmentProbe.NA, env.get("cpus_allowed"), "only Linux exposes Cpus_allowed_list");
		}
	}

	/** Linux 上 cpus_allowed 必须是真实的 Cpus_allowed_list（如 0-11 或 5-9），不是 n/a / unknown。 */
	@Test
	void cpusAllowedIsReadFromProcOnLinux() {
		assumeTrue(linux(), "Cpus_allowed_list only exists on Linux");
		Object cpus = EnvironmentProbe.collect().get("cpus_allowed");
		assertNotEquals(EnvironmentProbe.NA, cpus);
		assertNotEquals(EnvironmentProbe.UNKNOWN, cpus);
		assertTrue(String.valueOf(cpus).matches("[0-9]+([-,][0-9]+)*"), "unexpected Cpus_allowed_list: " + cpus);
	}

	/** available_cpus = JVM 可用核（受 taskset 影响），host_cpus = 整机逻辑核（不受影响）；旧字段 logical_cpus 已改名。 */
	@Test
	void availableAndHostCpusAreBothRecorded() {
		Map<String, Object> env = EnvironmentProbe.collect();
		assertEquals(Runtime.getRuntime().availableProcessors(), env.get("available_cpus"));
		Object host = env.get("host_cpus");
		assertTrue(host instanceof Integer, "host_cpus should be read from /proc/cpuinfo or NUMBER_OF_PROCESSORS: " + host);
		assertTrue((Integer) host >= Runtime.getRuntime().availableProcessors(), "host " + host + " < available");
		assertFalse(env.containsKey("logical_cpus"), "renamed to available_cpus");
		Map<String, String> flat = EnvironmentProbe.collectAsStrings();
		assertEquals(String.valueOf(host), flat.get("host_cpus"));
	}

	@Test
	void flattenedFormKeepsEveryFieldAsAString() {
		Map<String, String> flat = EnvironmentProbe.collectAsStrings();
		assertEquals(EnvironmentProbe.collect().keySet(), flat.keySet());
		assertEquals(String.valueOf(ProcessHandle.current().pid()), flat.get("pid"));
		assertFalse(flat.get("gc").startsWith("["), "lists are joined with spaces, not printed as [a, b]");
	}

	/** environment() 经真实 RPC 往返（protobuf 序列化 Map<String,String>）：服务端启动时采集的同一套字段原样返回。 */
	@Test
	void serverEnvironmentRoundTripsOverRpc() throws Exception {
		try (BenchServer server = BenchServer.start(0, "127.0.0.1", null, "protobuf");
				RpcClient client = RpcClient.builder().registry(DirectRegistryFactory.config("127.0.0.1:" + server.port())).build()) {
			BenchService service = client.reference(BenchService.class,
					ReferenceOptions.defaults().timeout(RpcCallTarget.ENVIRONMENT_TIMEOUT));
			Map<String, String> remote = service.environment();
			for (String key : new String[] { "os_name", "jvm_args", "cpus_allowed", "pid", "hostname", "netty_version",
					"harness_version" }) {
				assertTrue(remote.containsKey(key), "server environment lacks " + key + ": " + remote);
			}
			assertEquals(String.valueOf(ProcessHandle.current().pid()), remote.get("pid"), "in-process server: same pid");
			assertEquals(EnvironmentProbe.collectAsStrings().get("hostname"), remote.get("hostname"));
		}
	}
}
