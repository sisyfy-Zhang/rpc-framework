package com.feynman.rpc.bench;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 运行环境自动采集，进 JSON 的 {@code environment}。原则：<b>数字不手写、环境不手填</b>；任何一项采集失败都填
 * {@value #UNKNOWN}（或该项明确不适用时填 {@value #NA}），绝不抛异常——环境采集失败不该让一次压测作废。
 */
final class EnvironmentProbe {

	static final String UNKNOWN = "unknown";
	static final String NA = "n/a";
	private static final long COMMAND_TIMEOUT_SECONDS = 10;

	private EnvironmentProbe() {
	}

	static Map<String, Object> collect() {
		Map<String, Object> env = new LinkedHashMap<>();
		String osName = prop("os.name");
		boolean linux = osName.toLowerCase(Locale.ROOT).contains("linux");
		boolean windows = osName.toLowerCase(Locale.ROOT).contains("windows");
		env.put("os_name", osName);
		env.put("os_version", prop("os.version"));
		env.put("os_arch", prop("os.arch"));
		env.put("wsl", linux ? wsl() : (Object) false);
		env.put("cpu_model", safe(() -> linux ? linuxCpuModel() : windows ? windowsCpuModel() : UNKNOWN));
		// available_cpus：JVM 实际可用的核（受 taskset / cgroup 影响，线程池按它定大小）；host_cpus：整机逻辑核，不受绑核影响
		env.put("available_cpus", Runtime.getRuntime().availableProcessors());
		env.put("host_cpus", safe(() -> linux ? linuxHostCpus() : windows ? windowsHostCpus() : UNKNOWN));
		env.put("cpus_allowed", linux ? safe(EnvironmentProbe::cpusAllowed) : NA);
		env.put("total_memory_bytes", safe(EnvironmentProbe::totalMemory));
		env.put("jdk_version", prop("java.runtime.version"));
		env.put("jdk_vendor", prop("java.vendor"));
		env.put("jvm_name", prop("java.vm.name"));
		env.put("jvm_args", safe(() -> {
			RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
			return new ArrayList<Object>(runtime.getInputArguments());
		}));
		env.put("gc", safe(() -> {
			List<Object> names = new ArrayList<>();
			for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
				names.add(gc.getName());
			}
			return names;
		}));
		env.put("netty_version", safe(EnvironmentProbe::nettyVersion));
		Map<String, Object> git = git();
		env.put("git_sha", git.get("sha"));
		env.put("git_dirty", git.get("dirty"));
		env.put("git_dirty_files", git.get("dirty_files"));
		env.put("hostname", safe(EnvironmentProbe::hostname));
		env.put("power_supply", linux ? safe(EnvironmentProbe::powerSupply) : NA);
		env.put("cpu_governor", linux ? safe(EnvironmentProbe::governor) : NA);
		env.put("harness_version", harnessVersion());
		env.put("pid", ProcessHandle.current().pid());
		return env;
	}

	/**
	 * 同一套字段压平成字符串（列表以空格连接、映射写成 k=v），用于经 RPC 传输的服务端环境
	 * （{@link BenchService#environment()}）：返回类型固定为 {@code Map<String, String>}，与序列化器无关。
	 */
	static Map<String, String> collectAsStrings() {
		Map<String, String> flat = new LinkedHashMap<>();
		collect().forEach((key, value) -> flat.put(key, flatten(value)));
		return flat;
	}

	private static String flatten(Object value) {
		if (value instanceof List<?> list) {
			List<String> parts = new ArrayList<>();
			list.forEach(item -> parts.add(String.valueOf(item)));
			return String.join(" ", parts);
		}
		if (value instanceof Map<?, ?> map) {
			List<String> parts = new ArrayList<>();
			map.forEach((k, v) -> parts.add(k + "=" + v));
			return String.join(", ", parts);
		}
		return String.valueOf(value);
	}

	/**
	 * 主机逻辑核数：数 /proc/cpuinfo 的 {@code processor} 行（列出全部在线核，不受本进程亲和影响）；读不到时用
	 * {@code nproc --all}。
	 */
	private static Object linuxHostCpus() throws IOException {
		Path cpuinfo = Path.of("/proc/cpuinfo");
		if (Files.isReadable(cpuinfo)) {
			long count = Files.readAllLines(cpuinfo, StandardCharsets.UTF_8).stream()
					.filter(line -> line.startsWith("processor") && line.contains(":")).count();
			if (count > 0) {
				return (int) count;
			}
		}
		String nproc = run(null, "nproc", "--all");
		return nproc == null || nproc.isBlank() ? UNKNOWN : Integer.parseInt(nproc.trim());
	}

	/** Windows：环境变量 {@code NUMBER_OF_PROCESSORS}（系统逻辑核数，不受进程亲和影响）。 */
	private static Object windowsHostCpus() {
		String n = System.getenv("NUMBER_OF_PROCESSORS");
		return n == null || n.isBlank() ? UNKNOWN : Integer.parseInt(n.trim());
	}

	/** 本进程允许运行的 CPU（taskset / cgroup cpuset 的结果），读 /proc/self/status 的 Cpus_allowed_list。 */
	private static Object cpusAllowed() throws IOException {
		for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.UTF_8)) {
			if (line.startsWith("Cpus_allowed_list:")) {
				return line.substring(line.indexOf(':') + 1).trim();
			}
		}
		return UNKNOWN;
	}

	private interface Probe {
		Object get() throws Exception;
	}

	private static Object safe(Probe probe) {
		try {
			Object value = probe.get();
			return value == null ? UNKNOWN : value;
		} catch (Exception | LinkageError e) {
			return UNKNOWN;
		}
	}

	private static String prop(String key) {
		String value = System.getProperty(key);
		return value == null || value.isBlank() ? UNKNOWN : value;
	}

	private static Object wsl() {
		try {
			String version = Files.readString(Path.of("/proc/version"), StandardCharsets.UTF_8);
			return version.toLowerCase(Locale.ROOT).contains("microsoft");
		} catch (IOException | RuntimeException e) {
			return UNKNOWN;
		}
	}

	private static String linuxCpuModel() throws IOException {
		for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
			if (line.startsWith("model name")) {
				return line.substring(line.indexOf(':') + 1).trim();
			}
		}
		return UNKNOWN;
	}

	/** wmic 在新版 Windows 11 上已移除，失败再用 PowerShell 的 Get-CimInstance。 */
	private static String windowsCpuModel() {
		String wmic = run(null, "wmic", "cpu", "get", "name");
		if (wmic != null) {
			for (String line : wmic.split("\\R")) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase("Name")) {
					return trimmed;
				}
			}
		}
		String cim = run(null, "powershell", "-NoProfile", "-NonInteractive", "-Command",
				"(Get-CimInstance Win32_Processor | Select-Object -First 1).Name");
		return cim == null || cim.isBlank() ? UNKNOWN : cim.trim();
	}

	private static Object totalMemory() {
		if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
			return os.getTotalMemorySize();
		}
		return UNKNOWN;
	}

	private static Object nettyVersion() {
		var versions = io.netty.util.Version.identify();
		for (String artifact : List.of("netty-common", "netty-transport", "netty-all")) {
			if (versions.containsKey(artifact)) {
				return versions.get(artifact).artifactVersion();
			}
		}
		return versions.isEmpty() ? UNKNOWN : versions.values().iterator().next().artifactVersion();
	}

	/**
	 * Linux 先读内核的主机名（{@code HOSTNAME} 是 shell 变量、通常不导出；{@code InetAddress.getLocalHost()} 在 WSL
	 * 上常解析成 {@code localhost}），Windows 用 {@code COMPUTERNAME}，最后才退回 InetAddress。
	 */
	private static String hostname() throws IOException {
		Path kernel = Path.of("/proc/sys/kernel/hostname");
		if (Files.isReadable(kernel)) {
			String name = Files.readString(kernel, StandardCharsets.UTF_8).trim();
			if (!name.isEmpty()) {
				return name;
			}
		}
		String fromEnv = System.getenv("COMPUTERNAME");
		if (fromEnv == null || fromEnv.isBlank()) {
			fromEnv = System.getenv("HOSTNAME");
		}
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv;
		}
		return InetAddress.getLocalHost().getHostName();
	}

	/**
	 * git sha 与 dirty：在当前工作目录所在的仓库里执行。{@code core.autocrlf=true} 让从 Windows 同步到 WSL 的 CRLF
	 * 工作区不被误判为全部修改；{@code core.fileMode=false} 同理（同步后文件权限位会变）；归档目录 {@code bench/reports}
	 * 排除在外——否则上一次运行写出的报告会让下一次运行永远是 dirty。
	 */
	static Map<String, Object> git() {
		Map<String, Object> git = new LinkedHashMap<>();
		String sha = run(null, "git", "rev-parse", "HEAD");
		if (sha == null || sha.isBlank()) {
			git.put("sha", UNKNOWN);
			git.put("dirty", UNKNOWN);
			git.put("dirty_files", List.of());
			return git;
		}
		git.put("sha", sha.trim());
		String top = run(null, "git", "rev-parse", "--show-toplevel");
		String status = top == null ? null
				: run(Path.of(top.trim()), "git", "-c", "core.autocrlf=true", "-c", "core.fileMode=false", "status", "--porcelain",
						"--", ".", ":(exclude)bench/reports");
		if (status == null) {
			git.put("dirty", UNKNOWN);
			git.put("dirty_files", List.of());
			return git;
		}
		List<Object> files = new ArrayList<>();
		for (String line : status.split("\\R")) {
			if (!line.isBlank()) {
				files.add(line.strip());
			}
		}
		git.put("dirty", !files.isEmpty());
		git.put("dirty_files", files.size() > 50 ? new ArrayList<>(files.subList(0, 50)) : files);
		return git;
	}

	private static Object powerSupply() throws IOException {
		Path dir = Path.of("/sys/class/power_supply");
		if (!Files.isDirectory(dir)) {
			return NA;
		}
		Map<String, Object> online = new LinkedHashMap<>();
		try (DirectoryStream<Path> supplies = Files.newDirectoryStream(dir)) {
			for (Path supply : supplies) {
				Path file = supply.resolve("online");
				if (Files.isReadable(file)) {
					online.put(supply.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8).trim());
				}
			}
		}
		return online.isEmpty() ? NA : online;
	}

	private static Object governor() throws IOException {
		Path file = Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor");
		return Files.isReadable(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : NA;
	}

	static String harnessVersion() {
		try (InputStream in = EnvironmentProbe.class.getResourceAsStream("/bench-version.properties")) {
			if (in == null) {
				return UNKNOWN;
			}
			Properties properties = new Properties();
			properties.load(in);
			String version = properties.getProperty("harness.version");
			return version == null || version.isBlank() || version.startsWith("${") ? UNKNOWN : version;
		} catch (IOException e) {
			return UNKNOWN;
		}
	}

	/** 执行外部命令，成功（退出码 0）返回标准输出，否则 null。不继承标准输入，超时即杀掉。 */
	static String run(Path dir, String... command) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false)
					.redirectError(ProcessBuilder.Redirect.DISCARD).redirectInput(ProcessBuilder.Redirect.PIPE);
			if (dir != null) {
				builder.directory(dir.toFile());
			}
			Process process = builder.start();
			process.getOutputStream().close();
			// 输出在另一个线程里读：子进程卡住时（本机见过 powershell 的 Get-CimInstance 一直不退出）readAllBytes 会永远阻塞，
			// 超时判断必须不依赖读完输出
			java.util.concurrent.CompletableFuture<byte[]> out = new java.util.concurrent.CompletableFuture<>();
			Thread reader = Thread.ofVirtual().start(() -> {
				try {
					out.complete(process.getInputStream().readAllBytes());
				} catch (IOException e) {
					out.completeExceptionally(e);
				}
			});
			if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
				reader.interrupt();
				return null;
			}
			byte[] bytes = out.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return process.exitValue() == 0 ? new String(bytes, StandardCharsets.UTF_8) : null;
		} catch (IOException | RuntimeException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}
}
