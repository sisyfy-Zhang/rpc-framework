package com.feynman.rpc.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次运行怎么对待卡片的 {@code load.faults}（{@code run} 的 {@code --fault-schedule} / {@code --no-faults} / {@code --fault-variant} /
 * {@code --topology}），以及与驱动脚本之间的两个文件：
 *
 * <ul>
 *   <li><b>故障时间表</b>（{@code --fault-schedule <file>}）：本轮开始时由 harness 写出（先写临时文件再改名，脚本不会读到半个文件），
 *       一行一个 JSON 对象的形式太难用 shell 解析，所以用简单的行格式：
 *       <pre>
 *       # rpc-bench-e2e fault schedule v1
 *       mode inject|control
 *       round_start_epoch_ms 1760000000000
 *       fault &lt;index&gt; &lt;at_ms&gt; &lt;kind&gt; &lt;target&gt; [key=value ...]   （latency_ms / jitter_ms / timeout_ms / stream）
 *       </pre>
 *       {@code mode control}（{@code --no-faults}）时没有 {@code fault} 行：对照运行，驱动脚本什么都不做；</li>
 *   <li><b>执行记录</b>（{@code <file>.executed}）：驱动脚本每执行一项追加一行 {@code <index> <epoch_ms> <ok|failed> [detail]}；
 *       harness 在本轮结束后读回，换算成相对本轮开始的 {@code executed_at_ms}。</li>
 * </ul>
 * harness 自己不杀进程、不碰 toxiproxy：它只负责"什么时候该发生什么"与"实际发生了什么、对调用方意味着什么"。
 *
 * @param mode      {@value #MODE_INJECT}（执行卡片的故障）、{@value #MODE_CONTROL}（{@code --no-faults}，对照）或 {@value #MODE_NONE}（卡片没有故障）
 * @param variant   {@code --fault-variant}：把卡片里全部 {@code kill-9} 换成这一种（null = 不换）
 * @param schedule  时间表文件；null = 不写（{@code --no-faults} 时允许）
 * @param faults    实际交给驱动脚本的故障（已按 variant 替换；control 时仍是卡片的故障，只用来在报告里标出计划时刻）
 * @param topology  {@code --topology <file>} 读到的"提供者编号 → 公布地址"（fault-topology.sh 写出）；没有时为空
 */
record FaultPlan(String mode, String variant, Path schedule, List<Profile.Fault> faults, Map<String, String> topology,
		Map<String, Integer> controlPorts) {

	FaultPlan(String mode, String variant, Path schedule, List<Profile.Fault> faults, Map<String, String> topology) {
		this(mode, variant, schedule, faults, topology, Map.of());
	}

	static final String MODE_INJECT = "inject";
	static final String MODE_CONTROL = "control";
	static final String MODE_NONE = "none";
	static final String HEADER = "# rpc-bench-e2e fault schedule v1";

	static final FaultPlan NONE = new FaultPlan(MODE_NONE, null, null, List.of(), Map.of());

	boolean active() {
		return !MODE_NONE.equals(mode);
	}

	boolean inject() {
		return MODE_INJECT.equals(mode);
	}

	/**
	 * 按命令行与卡片决定。卡片有故障时必须给 {@code --fault-schedule}（注入）或 {@code --no-faults}（对照）之一：不给就跑，
	 * 卡片声明的故障不会发生，结果却挂着这张卡的 id。
	 *
	 * @throws IllegalArgumentException 选项组合不对
	 */
	static FaultPlan of(Profile profile, Path schedule, boolean noFaults, String variant, Path topologyFile) throws IOException {
		List<Profile.Fault> cardFaults = profile.load().faults();
		Map<String, String> topology = topologyFile == null ? Map.of() : readTopology(topologyFile);
		Map<String, Integer> controlPorts = topologyFile == null ? Map.of() : readControlPorts(topologyFile);
		if (cardFaults.isEmpty()) {
			if (schedule != null || noFaults || variant != null) {
				throw new IllegalArgumentException("--fault-schedule / --no-faults / --fault-variant need a card with load.faults");
			}
			return new FaultPlan(MODE_NONE, null, null, List.of(), topology, controlPorts);
		}
		if (variant != null && !Profile.FAULT_VARIANTS.contains(variant)) {
			throw new IllegalArgumentException("--fault-variant must be one of " + new java.util.TreeSet<>(Profile.FAULT_VARIANTS) + ", got \""
					+ variant + "\"");
		}
		List<Profile.Fault> faults = new ArrayList<>();
		for (Profile.Fault f : cardFaults) {
			faults.add(variant != null && Profile.KILL_9.equals(f.kind()) ? f.withKind(variant) : f);
		}
		if (variant != null && faults.equals(cardFaults) && !Profile.KILL_9.equals(variant)) {
			throw new IllegalArgumentException("--fault-variant " + variant + " replaces kill-9 faults, but the card has none");
		}
		if (noFaults) {
			return new FaultPlan(MODE_CONTROL, variant, schedule, faults, topology, controlPorts);
		}
		if (schedule == null) {
			throw new IllegalArgumentException("the card declares load.faults: give --fault-schedule <file> (bench/bin/fault-run.sh executes it) "
					+ "or --no-faults (control run)");
		}
		return new FaultPlan(MODE_INJECT, variant, schedule, faults, topology, controlPorts);
	}

	/**
	 * 拓扑文件第 6 列（有时）：该提供者的 HTTP 控制端口（{@code server --http-port}），键是公布地址。harness 经它每秒取
	 * {@code GET /counters}——在 IO 线程上回答、不经业务执行器，执行器排满时也取得到。
	 */
	static Map<String, Integer> readControlPorts(Path file) throws IOException {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String t = line.trim();
			if (t.isEmpty() || t.startsWith("#")) {
				continue;
			}
			String[] parts = t.split("\\s+");
			if (parts.length >= 6) {
				try {
					out.put(parts[1], Integer.parseInt(parts[5]));
				} catch (NumberFormatException ignored) {
					// "-"：没有控制端口
				}
			}
		}
		return out;
	}

	/** {@code fault-topology.sh} 写出的拓扑：每行 {@code <编号> <公布地址> ...}，{@code #} 开头为注释。 */
	static Map<String, String> readTopology(Path file) throws IOException {
		Map<String, String> out = new LinkedHashMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String t = line.trim();
			if (t.isEmpty() || t.startsWith("#")) {
				continue;
			}
			String[] parts = t.split("\\s+");
			if (parts.length >= 2) {
				out.put(parts[0], parts[1]);
			}
		}
		return out;
	}

	/** 本轮开始时写时间表（原子改名）。 */
	void writeSchedule(long roundStartEpochMs) throws IOException {
		if (schedule == null) {
			return;
		}
		StringBuilder text = new StringBuilder(HEADER).append('\n');
		text.append("mode ").append(mode).append('\n');
		text.append("round_start_epoch_ms ").append(roundStartEpochMs).append('\n');
		if (inject()) {
			for (int i = 0; i < faults.size(); i++) {
				Profile.Fault f = faults.get(i);
				text.append("fault ").append(i).append(' ').append(Math.round(Durations.millis(f.at()))).append(' ').append(f.kind()).append(' ')
						.append(f.target());
				if (f.latency() != null) {
					text.append(" latency_ms=").append(Math.round(Durations.millis(f.latency())));
				}
				if (f.jitter() != null) {
					text.append(" jitter_ms=").append(Math.round(Durations.millis(f.jitter())));
				}
				if (f.timeout() != null) {
					text.append(" timeout_ms=").append(Math.round(Durations.millis(f.timeout())));
				}
				if (f.stream() != null) {
					text.append(" stream=").append(f.stream());
				}
				text.append('\n');
			}
		}
		Path parent = schedule.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = schedule.resolveSibling(schedule.getFileName() + ".tmp");
		Files.writeString(tmp, text, StandardCharsets.UTF_8);
		Files.move(tmp, schedule, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** 执行记录文件：{@code <schedule>.executed}。 */
	Path executedFile() {
		return schedule == null ? null : schedule.resolveSibling(schedule.getFileName() + ".executed");
	}

	/** 本轮开始前清掉上一次的执行记录（同一路径复用时不能读到旧的）。 */
	void clearExecuted() throws IOException {
		if (executedFile() != null) {
			Files.deleteIfExists(executedFile());
		}
	}

	/**
	 * 读回执行记录：按故障序号排列，{@code {index, executed_at_ms, status, detail}}；没有记录的项不出现（之后记为 not_executed）。
	 */
	List<Map<String, Object>> readExecuted(long roundStartEpochMs) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < faults.size(); i++) {
			out.add(null);
		}
		Path file = executedFile();
		if (file == null || !Files.exists(file)) {
			return compact(out);
		}
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String[] parts = line.trim().split("\\s+", 4);
				if (parts.length < 3) {
					continue;
				}
				try {
					int index = Integer.parseInt(parts[0]);
					long epoch = Long.parseLong(parts[1]);
					if (index < 0 || index >= out.size()) {
						continue;
					}
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("index", index);
					e.put("executed_at_ms", (double) (epoch - roundStartEpochMs));
					e.put("status", parts[2]);
					e.put("detail", parts.length > 3 ? parts[3] : null);
					out.set(index, e);
				} catch (NumberFormatException ignored) {
					// 不认识的行跳过
				}
			}
		} catch (IOException e) {
			return compact(out);
		}
		return compact(out);
	}

	/** 保持"第 i 项对应第 i 个故障"：没有记录的位置放一个只有 index 的占位（status 留给调用方判 not_executed）。 */
	private static List<Map<String, Object>> compact(List<Map<String, Object>> entries) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < entries.size(); i++) {
			Map<String, Object> e = entries.get(i);
			if (e == null) {
				e = new LinkedHashMap<>();
				e.put("index", i);
				e.put("executed_at_ms", null);
				e.put("status", "not_executed");
			}
			out.add(e);
		}
		return out;
	}

	/** 进 JSON 的 {@code run.faults}。 */
	Map<String, Object> toJson() {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("mode", mode);
		json.put("variant", variant);
		json.put("schedule_file", schedule == null ? null : schedule.toString());
		List<Object> list = new ArrayList<>();
		faults.forEach(f -> list.add(Profile.faultJson(f)));
		json.put("faults", list);
		json.put("topology", topology.isEmpty() ? null : new LinkedHashMap<>(topology));
		json.put("control_ports", controlPorts.isEmpty() ? null : new LinkedHashMap<>(controlPorts));
		json.put("executor", "bench/bin/fault-run.sh (harness writes the schedule, the script executes it and appends <schedule>.executed)");
		return json;
	}
}
