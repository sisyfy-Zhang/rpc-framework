#!/usr/bin/env bash
# harness 交叉验证（Linux / WSL2）：同一个 HTTP 目标先用 harness（transport: http 口径卡）打，再用 wrk / wrk2 打。
# 口径见 docs/benchmarking.md「交叉验证」：
#   bench/bin/crossval.sh --mode open     # 测量校验：crossval-http-open-v1 vs wrk2 -R 5000 -U（达成吞吐 / 服务时间 vs 未修正延迟 / 发出滞后）
#   bench/bin/crossval.sh --mode closed   # 发压容量标定：crossval-http-closed-capacity-v1 vs wrk（两边吞吐 + 两侧 CPU，判别瓶颈侧）
#
# 选项：--out <dir>（缺省 target/crossval，已被 .gitignore 忽略；归档时传该 sha 的归档目录 bench/reports/<yyyyMMdd>-<sha7>，
#             交叉验证结果与同一 sha 的压测结果放在一起，作为 harness 可信度的证据，bench-report.sh 汇总时一并列出判定）
#       --label <text>（缺省 crossval；透传给 harness 的 run --label，写进 harness 结果 JSON）
#       --profile <card.yaml>（缺省按 mode 选仓库里的卡；只在调试脚本时换成短卡）
# 环境变量：BENCH_SERVER_CPUS（缺省 0-4）、BENCH_CLIENT_CPUS（缺省 5-9，harness 与 wrk/wrk2 用同一组核）、
#           CROSSVAL_TOOLS_BIN（wrk / wrk2 所在目录，缺省 ~/tools/bin）、
#           CROSSVAL_TOOL_WARMUP / CROSSVAL_TOOL_DURATION（工具每次的预热 / 测量秒数，缺省 20 / 40，与卡片的预热与窗口长度一致）、
#           CROSSVAL_TOOL_RUNS（工具测几次，缺省开环 3 次取未修正 p99 中位、闭环 1 次）。
# 流程：起 bench server（RPC 19100 + HTTP 19180，--no-registry）→ harness 跑卡（每轮统计窗口自带 CPU 采样）→ 工具预热一次（丢弃）
#       → 工具测量（开环 wrk2 带 -U 测 3 次、每次先预热，闭环 wrk 1 次；第 1 次测量中段采样 /proc/stat）→ 停服务端
#       → rpc-bench-e2e crossval 比较并写 crossval-<mode>-<时间>.json / .md。
# 退出码：0 PASS；2 FAIL（判定未通过）；1 运行出错。先构建：mvn -pl rpc-bench-e2e -am package -DskipTests
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/rpc-bench-e2e"

MODE=""
OUT="$ROOT/target/crossval"
LABEL="crossval"
PROFILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --label) LABEL="${2:-}"; shift 2 ;;
    --profile) PROFILE="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1 (usage: crossval.sh --mode open|closed [--out <dir>] [--label <text>] [--profile <card.yaml>])" >&2; exit 1 ;;
  esac
done
if [ -z "$OUT" ] || [ -z "$LABEL" ]; then
  echo "--out and --label need a non-empty value" >&2
  exit 1
fi

TOOLS_BIN="${CROSSVAL_TOOLS_BIN:-$HOME/tools/bin}"
SERVER_CPUS="${BENCH_SERVER_CPUS:-0-4}"
CLIENT_CPUS="${BENCH_CLIENT_CPUS:-5-9}"
TOOL_WARMUP="${CROSSVAL_TOOL_WARMUP:-20}"
TOOL_DURATION="${CROSSVAL_TOOL_DURATION:-40}"
TOOL_RUNS_SET="${CROSSVAL_TOOL_RUNS:-}"
RPC_PORT=19100
HTTP_PORT=19180
URL="http://127.0.0.1:${HTTP_PORT}/echo"
SCRIPT="$ROOT/bench/bin/post-1k.lua"

case "$MODE" in
  open)
    PROFILE="${PROFILE:-$ROOT/bench/profiles/crossval-http-open-v1.yaml}"
    TOOL=wrk2
    TOOL_ARGS=(-t 4 -c 64 -R 5000 --latency -U -s "$SCRIPT")
    TOOL_RUNS="${TOOL_RUNS_SET:-3}"
    ;;
  closed)
    PROFILE="${PROFILE:-$ROOT/bench/profiles/crossval-http-closed-capacity-v1.yaml}"
    TOOL=wrk
    TOOL_ARGS=(-t 4 -c 64 --latency -s "$SCRIPT")
    TOOL_RUNS="${TOOL_RUNS_SET:-1}"
    ;;
  *) echo "--mode must be open or closed" >&2; exit 1 ;;
esac

if [ ! -d "$MODULE/target/lib" ]; then
  echo "rpc-bench-e2e is not built; run: mvn -pl rpc-bench-e2e -am package -DskipTests" >&2
  exit 1
fi
TOOL_BIN="$TOOLS_BIN/$TOOL"
if [ ! -x "$TOOL_BIN" ]; then
  echo "$TOOL not found at $TOOL_BIN (set CROSSVAL_TOOLS_BIN)" >&2
  exit 1
fi
PIN=()
if command -v taskset >/dev/null 2>&1; then
  PIN=(taskset -c "$CLIENT_CPUS")
else
  echo "warning: taskset is not available; $TOOL runs unpinned" >&2
fi
CP="$MODULE/target/classes:$MODULE/target/lib/*"

STAMP="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="$OUT/run-$MODE-$STAMP"
mkdir -p "$RUN_DIR"
echo "crossval $MODE: profile $PROFILE, label $LABEL, tool $TOOL_BIN, server cpus $SERVER_CPUS, client cpus $CLIENT_CPUS -> $RUN_DIR"

# 工具运行期间的各核利用率采样（/proc/stat）：在后台等 $1 秒后采样 $2 秒，把一行结果写进 $3（格式由 crossval 子命令解析）
expand_cpus() { # "0-4,7" -> "0 1 2 3 4 7"
  local out="" part
  for part in ${1//,/ }; do
    if [[ "$part" == *-* ]]; then out="$out $(seq -s ' ' "${part%-*}" "${part#*-}")"; else out="$out $part"; fi
  done
  echo $out
}
cpu_snapshot() { awk '/^cpu[0-9]/ {n=substr($1,4); idle=$5+$6; t=0; for(i=2;i<=NF;i++) t+=$i; print n, idle, t}' /proc/stat; }
sample_cpu() {
  local start="$1" len="$2" out="$3" a b
  sleep "$start"
  a="$(cpu_snapshot)"
  sleep "$len"
  b="$(cpu_snapshot)"
  awk -v server="$(expand_cpus "$SERVER_CPUS")" -v client="$(expand_cpus "$CLIENT_CPUS")" -v len="$len" '
    NR==FNR { idle[$1]=$2; tot[$1]=$3; next }
    { di=$2-idle[$1]; dt=$3-tot[$1]; u[$1]=(dt>0 ? 100*(dt-di)/dt : 0); if ($1>max) max=$1 }
    END {
      ns=split(server, s, " "); nc=split(client, c, " ");
      for (i=1;i<=ns;i++) ss+=u[s[i]]; for (i=1;i<=nc;i++) cs+=u[c[i]];
      line=sprintf("服务端核(%s) 平均 %.0f%% · 压测端核(%s) 平均 %.0f%%（%ss 采样）·", server, ss/ns, client, cs/nc, len);
      for (i=0;i<=max;i++) line=line sprintf(" cpu%d=%.0f%%", i, u[i]);
      print line
    }' <(echo "$a") <(echo "$b") > "$out"
}

SERVER_PID=""
stop_server() {
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill -TERM "$SERVER_PID" 2>/dev/null || true
    for _ in $(seq 1 100); do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 0.1
    done
    if kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "warning: bench server $SERVER_PID did not stop after SIGTERM; killing it" >&2
      kill -KILL "$SERVER_PID" 2>/dev/null || true
    fi
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  SERVER_PID=""
}
trap stop_server EXIT INT TERM

# 1. 服务端（bench-server.sh 以 exec 启动 java，所以 $! 就是 JVM 的 pid）
BENCH_SERVER_CPUS="$SERVER_CPUS" "$ROOT/bench/bin/bench-server.sh" --port "$RPC_PORT" --host 127.0.0.1 --no-registry \
  --http-port "$HTTP_PORT" > "$RUN_DIR/server.log" 2>&1 < /dev/null &
SERVER_PID=$!
for _ in $(seq 1 120); do
  grep -q "bench-server ready" "$RUN_DIR/server.log" && break
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "bench server exited before it was ready:" >&2
    cat "$RUN_DIR/server.log" >&2
    exit 1
  fi
  sleep 0.5
done
if ! grep -q "bench-server ready" "$RUN_DIR/server.log"; then
  echo "bench server not ready after 60s:" >&2
  cat "$RUN_DIR/server.log" >&2
  exit 1
fi
cat "$RUN_DIR/server.log"

# 2. harness（退出码 2 = 卡片判定未通过，照样比较；1 = 运行出错，中止）。CPU 由 run 自己在每轮统计窗口里采样（rounds[].cpu）
set +e
BENCH_CLIENT_CPUS="$CLIENT_CPUS" "$ROOT/bench/bin/bench-run.sh" --profile "$PROFILE" --out "$RUN_DIR" --label "$LABEL" \
  2>&1 | tee "$RUN_DIR/harness.log"
HARNESS_CODE=${PIPESTATUS[0]}
set -e
if [ "$HARNESS_CODE" -ne 0 ] && [ "$HARNESS_CODE" -ne 2 ]; then
  echo "harness run failed (exit $HARNESS_CODE)" >&2
  exit 1
fi
HARNESS_JSON="$(sed -n 's/^json: //p' "$RUN_DIR/harness.log" | tail -1)"
if [ -z "$HARNESS_JSON" ] || [ ! -f "$HARNESS_JSON" ]; then
  echo "harness did not report a result JSON" >&2
  exit 1
fi

# 3. 工具：每次先预热一次（丢弃），再测量一次；时长与 harness 的预热 / 统计窗口一致。开环（wrk2）缺省测 3 次，
#    crossval 取未修正 p99 的中位那一次作对照值（对称口径：与 harness 的 3 轮取中位一致）；闭环（wrk）缺省 1 次。
#    CPU 在第 1 次测量的中段采样。
TOOL_VERSION="$("$TOOL_BIN" -v 2>&1 | head -1 || true)"
TOOL_CMD="${PIN[*]} $TOOL ${TOOL_ARGS[*]} -d ${TOOL_DURATION}s $URL"
TOOL_CMD="${TOOL_CMD/#$ROOT\//}"
TOOL_CMD="${TOOL_CMD//$ROOT\//}"
TOOL_OUTPUTS=""
for run in $(seq 1 "$TOOL_RUNS"); do
  echo "$TOOL run $run/$TOOL_RUNS: warmup ${TOOL_WARMUP}s (discarded) ..."
  "${PIN[@]}" "$TOOL_BIN" "${TOOL_ARGS[@]}" -d "${TOOL_WARMUP}s" "$URL" > "$RUN_DIR/$TOOL-warmup-$run.txt" 2>&1
  echo "$TOOL run $run/$TOOL_RUNS: measure ${TOOL_DURATION}s: $TOOL_CMD"
  if [ "$run" -eq 1 ]; then
    sample_cpu "$((TOOL_DURATION / 4))" "$((TOOL_DURATION / 2))" "$RUN_DIR/cpu-tool.txt" &
    SAMPLER_PID=$!
  fi
  "${PIN[@]}" "$TOOL_BIN" "${TOOL_ARGS[@]}" -d "${TOOL_DURATION}s" "$URL" > "$RUN_DIR/$TOOL-measure-$run.txt" 2>&1
  if [ "$run" -eq 1 ]; then
    wait "$SAMPLER_PID" 2>/dev/null || true
    echo "cpu during $TOOL: $(cat "$RUN_DIR/cpu-tool.txt" 2>/dev/null)"
  fi
  sed '/Detailed Percentile spectrum/,/^-----/d' "$RUN_DIR/$TOOL-measure-$run.txt"
  TOOL_OUTPUTS="${TOOL_OUTPUTS:+$TOOL_OUTPUTS,}$RUN_DIR/$TOOL-measure-$run.txt"
done

# 4. 停服务端，比较
stop_server
set +e
"$JAVA_HOME/bin/java" -cp "$CP" com.feynman.rpc.bench.Bench crossval --harness "$HARNESS_JSON" --tool "$TOOL" \
  --tool-output "$TOOL_OUTPUTS" --tool-command "$TOOL_CMD" --tool-version "$TOOL_VERSION" \
  --cpu-tool "$(cat "$RUN_DIR/cpu-tool.txt" 2>/dev/null || echo unknown)" --out "$OUT"
CODE=$?
set -e
exit "$CODE"
