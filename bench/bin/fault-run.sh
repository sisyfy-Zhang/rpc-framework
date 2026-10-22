#!/usr/bin/env bash
# 故障注入实验的一次运行（Linux / WSL2）：起拓扑 → 起 harness（run --fault-schedule）→ 按 harness 写出的故障时间表在对应时刻执行故障
# （kill -9 / SIGTERM / SIGSTOP / SIGCONT、toxiproxy toxic 的增删）并回写执行记录 → 等 harness 结束 → 拆拓扑。
# harness 自己不碰进程与 toxiproxy：它只写"何时该发生什么"、读回"实际何时发生了什么"，并记录每秒时间线（docs/benchmarking.md「故障注入」）。
#   bench/bin/fault-run.sh --card fault-kill-node-failover-v1
#   bench/bin/fault-run.sh --card fault-kill-node-failover-v1 --variant sigterm        # 优雅停机对照
#   bench/bin/fault-run.sh --card fault-kill-node-failover-v1 --variant toxic-timeout  # 静默死亡：目标经 toxiproxy，timeout toxic 让连接挂住
#   bench/bin/fault-run.sh --card fault-slow-node-p2c-v1 --behind-toxiproxy all        # 三个提供者都经代理（代理路径本身的开销对称）
#   bench/bin/fault-run.sh --card fault-slow-node-p2c-v1 --behind-toxiproxy all --no-faults   # 对照：同卡不注入
#
# 选项：--card <id 或 yaml 路径>（id 时在 --profiles 目录下找，缺省 bench/profiles）；--variant <kind>（把卡里的 kill-9 换成它）；--no-faults；
#       --behind-toxiproxy <编号,...|all|none>（缺省：卡片里 toxic-* 故障的目标；--variant toxic-timeout 时加上 kill-9 的目标）；
#       --out <dir>（缺省 target/fault/run-<时间>）；--label <text>（缺省 fault-probe）；--server-args "<追加给每个服务端>"；
#       --base-port <p>（缺省 19401）。
# 环境变量：BENCH_SERVER_CPUS / BENCH_CLIENT_CPUS（缺省 0-4 / 5-9）；FAULT_HARNESS_OPTS（追加给 harness，缺省 "--open-workers per-request"：
#           开环每个请求一个虚拟线程，故障期间挂住的调用不会占满固定的 64 个工作者、把发压本身变成闭环）；TOXIPROXY_* 见 fault-topology.sh。
# 退出码：harness 的退出码（0 通过 / 2 判定未通过，例如故障没执行成功）；1 = 运行出错。
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOXIPROXY_API="${TOXIPROXY_API:-http://127.0.0.1:8474}"
TOPOLOGY="$ROOT/bench/bin/fault-topology.sh"

CARD=""
VARIANT=""
NO_FAULTS=0
BEHIND=""
OUT=""
LABEL="fault-probe"
SERVER_ARGS=""
BASE_PORT=19401
PROFILES="$ROOT/bench/profiles"
while [ $# -gt 0 ]; do
  case "$1" in
    --card) CARD="$2"; shift 2 ;;
    --variant) VARIANT="$2"; shift 2 ;;
    --no-faults) NO_FAULTS=1; shift ;;
    --behind-toxiproxy) BEHIND="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --server-args) SERVER_ARGS="$2"; shift 2 ;;
    --base-port) BASE_PORT="$2"; shift 2 ;;
    --profiles) PROFILES="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; sed -n '2,20p' "$0" >&2; exit 1 ;;
  esac
done
[ -n "$CARD" ] || { echo "--card is required" >&2; exit 1; }
case "$CARD" in *.yaml) CARD_FILE="$CARD" ;; *) CARD_FILE="$PROFILES/$CARD.yaml" ;; esac
[ -f "$CARD_FILE" ] || { echo "card not found: $CARD_FILE" >&2; exit 1; }
OUT="${OUT:-$ROOT/target/fault/run-$(date +%Y%m%d-%H%M%S)}"
case "$OUT" in /*) ;; *) OUT="$ROOT/$OUT" ;; esac
mkdir -p "$OUT"

# ---- 从卡片读拓扑与服务端配置（只看两格缩进的 target 字段，与 lib-bench.sh 的 card_server_executor 同一做法）
card_field() { sed -n "s/^  $1:[[:space:]]*\\([^ #]*\\).*/\\1/p" "$CARD_FILE" | head -n 1; }
PROVIDERS="$(card_field providers)"
EXECUTOR="$(card_field server_executor)"
QUEUE="$(card_field server_queue_capacity)"
WORK="$(card_field server_work)"
WORK_MODE="$(card_field server_work_mode)"
GRACE="$(card_field server_deregister_grace)"
SARGS=()
[ -n "$EXECUTOR" ] && [ "$EXECUTOR" != fixed ] && SARGS+=(--executor "$EXECUTOR")
[ -n "$QUEUE" ] && SARGS+=(--executor-queue "$QUEUE")
[ -n "$WORK" ] && SARGS+=(--work "$WORK")
[ -n "$WORK_MODE" ] && SARGS+=(--work-mode "$WORK_MODE")
[ -n "$GRACE" ] && SARGS+=(--deregister-grace "$GRACE")
# shellcheck disable=SC2206
SARGS+=($SERVER_ARGS)
# 卡片里的故障行（- { at: ..., kind: ..., target: ... }）：缺省让 toxic 的目标经代理
if [ -z "$BEHIND" ]; then
  targets=""
  while read -r line; do
    kind="$(echo "$line" | sed -n 's/.*kind:[[:space:]]*\([a-z0-9-]*\).*/\1/p')"
    target="$(echo "$line" | sed -n 's/.*target:[[:space:]]*\([0-9]*\).*/\1/p')"
    case "$kind" in
      toxic-*|remove-toxic) targets="$targets,$target" ;;
      kill-9) [ "$VARIANT" = toxic-timeout ] && targets="$targets,$target" ;;
    esac
  done < <(grep -E '^\s*- \{ *at:' "$CARD_FILE" || true)
  BEHIND="${targets#,}"
  BEHIND="${BEHIND:-none}"
fi

echo "fault-run: card $(basename "$CARD_FILE" .yaml), providers $PROVIDERS, behind toxiproxy: $BEHIND, variant: ${VARIANT:-none}, no-faults: $NO_FAULTS -> $OUT"
bash "$TOPOLOGY" wait-zk-clean --timeout 60
cleanup() {
  [ -n "${HARNESS_PID:-}" ] && kill -TERM "$HARNESS_PID" 2>/dev/null || true
  bash "$TOPOLOGY" down --out "$OUT" || true
}
trap cleanup EXIT INT TERM
bash "$TOPOLOGY" up --providers "$PROVIDERS" --out "$OUT" --behind-toxiproxy "$BEHIND" --base-port "$BASE_PORT" --server-args "${SARGS[*]:-}"
TOPO="$OUT/topology.txt"

SCHEDULE="$OUT/schedule-$(date +%H%M%S).txt"
HARGS=(--profile "$CARD_FILE" --registry zk://127.0.0.1:2181 --out "$OUT" --label "$LABEL" --topology "$TOPO")
HAS_FAULTS=0
grep -qE '^  faults:' "$CARD_FILE" && HAS_FAULTS=1
if [ "$HAS_FAULTS" = 0 ]; then
  # 卡片没有故障（例如只有阶梯的突增卡）：不写时间表，脚本只负责拓扑
  [ "$NO_FAULTS" = 0 ] && [ -z "$VARIANT" ] || { echo "--no-faults / --variant need a card with load.faults" >&2; exit 1; }
  NO_FAULTS=2
elif [ "$NO_FAULTS" = 1 ]; then HARGS+=(--no-faults); else HARGS+=(--fault-schedule "$SCHEDULE"); fi
[ -n "$VARIANT" ] && HARGS+=(--fault-variant "$VARIANT")
# shellcheck disable=SC2206
HOPTS=(${FAULT_HARNESS_OPTS:---open-workers per-request})
BENCH_CLIENT_CPUS="${BENCH_CLIENT_CPUS:-5-9}" "$ROOT/bench/bin/bench-run.sh" "${HARGS[@]}" "${HOPTS[@]}" > "$OUT/harness.log" 2>&1 < /dev/null &
HARNESS_PID=$!

# 毫秒墙钟（uutils 的 date 不支持 %3N，用纳秒除）
now_ms() { echo $(( $(date +%s%N) / 1000000 )); }
topo_field() { awk -v i="$1" -v f="$2" '$1==i {print $f}' "$TOPO"; }
toxic_add() { # toxic_add <proxy> <name> <type> <stream> <attributes-json>
  curl -fsS -X POST "$TOXIPROXY_API/proxies/$1/toxics" \
    -d "{\"name\":\"$2\",\"type\":\"$3\",\"stream\":\"$4\",\"toxicity\":1.0,\"attributes\":$5}" >/dev/null
}

# 执行一项故障；成功打印 detail 并返回 0
execute_fault() {
  local kind="$1" target="$2" params="$3" pid proxy stream latency jitter timeout names n
  pid="$(topo_field "$target" 3)"
  proxy="$(topo_field "$target" 5)"
  stream="$(echo "$params" | sed -n 's/.*stream=\([a-z]*\).*/\1/p')"
  case "$kind" in
    kill-9) kill -KILL "$pid" && echo "SIGKILL pid $pid" ;;
    sigterm) kill -TERM "$pid" && echo "SIGTERM pid $pid" ;;
    sigstop) kill -STOP "$pid" && echo "SIGSTOP pid $pid" ;;
    sigcont) kill -CONT "$pid" && echo "SIGCONT pid $pid" ;;
    toxic-latency|toxic-timeout)
      [ "$proxy" != "-" ] || { echo "provider $target is not behind toxiproxy (start with --behind-toxiproxy $target)"; return 1; }
      if [ "$kind" = toxic-latency ]; then
        latency="$(echo "$params" | sed -n 's/.*latency_ms=\([0-9]*\).*/\1/p')"
        jitter="$(echo "$params" | sed -n 's/.*jitter_ms=\([0-9]*\).*/\1/p')"
        attrs="{\"latency\":$latency,\"jitter\":${jitter:-0}}"
        type=latency
      else
        timeout="$(echo "$params" | sed -n 's/.*timeout_ms=\([0-9]*\).*/\1/p')"
        attrs="{\"timeout\":${timeout:-0}}"
        type=timeout
      fi
      n=0
      for s in downstream upstream; do
        if [ "$stream" = both ] || [ "$stream" = "$s" ]; then
          toxic_add "$proxy" "fault-$type-$s" "$type" "$s" "$attrs" && n=$((n + 1))
        fi
      done
      echo "$type toxic on $proxy ($stream) $attrs"
      ;;
    remove-toxic)
      [ "$proxy" != "-" ] || { echo "provider $target is not behind toxiproxy"; return 1; }
      names="$(curl -fsS "$TOXIPROXY_API/proxies/$proxy/toxics" | grep -o '"name":"[^"]*"' | cut -d'"' -f4 || true)"
      for name in $names; do curl -fsS -X DELETE "$TOXIPROXY_API/proxies/$proxy/toxics/$name" >/dev/null; done
      echo "removed toxics on $proxy: ${names//$'\n'/ }"
      ;;
    *) echo "unknown fault kind $kind"; return 1 ;;
  esac
}

# 等时间表（harness 在本轮开始时写出；预热段之后）
while [ ! -f "$SCHEDULE" ] && [ "$NO_FAULTS" = 0 ]; do
  kill -0 "$HARNESS_PID" 2>/dev/null || break
  sleep 0.01
done
if [ "$NO_FAULTS" = 0 ] && [ -f "$SCHEDULE" ]; then
  start="$(sed -n 's/^round_start_epoch_ms \([0-9]*\)$/\1/p' "$SCHEDULE")"
  echo "fault-run: round started at epoch ms $start; schedule:"
  grep '^fault ' "$SCHEDULE" || echo "  (no faults: mode $(sed -n 's/^mode //p' "$SCHEDULE"))"
  while read -r tag index at kind target params; do
    [ "$tag" = fault ] || continue
    due=$((start + at))
    while :; do
      left=$((due - $(now_ms)))
      [ "$left" -le 0 ] && break
      if [ "$left" -gt 50 ]; then sleep "$(awk -v l="$left" 'BEGIN{printf "%.3f", (l-20)/1000}')"; else sleep 0.002; fi
    done
    fired="$(now_ms)"
    if detail="$(execute_fault "$kind" "$target" "$params" 2>&1)"; then status=ok; else status=failed; fi
    echo "$index $fired $status $detail" >> "$SCHEDULE.executed"
    echo "fault-run: [$((fired - start))ms] fault $index $kind #$target -> $status: $detail"
  done < "$SCHEDULE"
fi

set +e
wait "$HARNESS_PID"
CODE=$?
set -e
HARNESS_PID=""
tail -n 4 "$OUT/harness.log"
echo "fault-run: harness exit $CODE; results in $OUT"
exit "$CODE"
