#!/usr/bin/env bash
# 并发扫描（Linux / WSL2）：本框架闭环 64 / 128 / 256 并发（compare-closed64-rpc-v1、sweep-closed128-rpc-v1、sweep-closed256-rpc-v1，
# 三张卡只差 load.concurrency，--direct 直连）对同一个服务端依次跑，结果写进同一目录，最后生成按并发并排的对比表 compare.md。
# 目的：找吞吐不再随并发增长的点（拐点）与此时两侧的 CPU——服务端核饱和说明到了服务端上限，压测端核饱和（client_bound）说明是发压上限。
#   bench/bin/sweep.sh
#   bench/bin/sweep.sh --concurrency 64,128,256,512 --out bench/reports/<yyyyMMdd>-<sha7>/sweep   # 512 需要先有 sweep-closed512-rpc-v1
#   bench/bin/sweep.sh --profiles ~/t11/profiles --suffix short --label smoke
#   bench/bin/sweep.sh --connections 2,4,8       # 连接数扫描：闭环 128 并发，每节点 2（sweep-closed128-rpc，缺省）/ 4 / 8 条连接（sweep-c<N>-closed128-rpc）
#   bench/bin/sweep.sh --cards sweep-c1-closed64-rpc-v1,compare-closed64-rpc-v1   # 任意几张 rpc 卡对同一个服务端依次跑（单连接上限：1 vs 2 条）
#
# 选项：--concurrency <n,n,...>（缺省 64,128,256；64 用 compare-closed64-rpc-<s>，其余用 sweep-closed<n>-rpc-<s>）；
#       --connections <n,n,...>（给了就改做连接数扫描：并发固定 128，2 用 sweep-closed128-rpc-<s>，其余用 sweep-c<n>-closed128-rpc-<s>）；
#       --cards <id,id,...>（给了就只跑这几张卡：<profiles>/<id>.yaml，不加后缀；各卡的 target.server_executor 必须一致，
#       不是 fixed 时给服务端传 --executor <它>；target.server_work / server_work_mode / server_queue_capacity 同样必须一致，
#       写了就给服务端传 --work / --work-mode / --executor-queue）；
#       --profiles <dir>（缺省 bench/profiles）、--suffix <s>（缺省 v1）、--out <dir>（缺省 target/sweep/run-<时间>）、--label <text>（缺省 sweep）。
# 环境变量：BENCH_SERVER_CPUS / BENCH_CLIENT_CPUS（缺省 0-4 / 5-9）；SWEEP_SERVER_ARGS（追加给服务端，例如 "--io-threads 5"、
#           "--flush-consolidation off"）；BENCH_HARNESS_OPTS（追加给每次 run，例如 "--io-threads 2"、"--flush-consolidation off"）；
#           BENCH_JFR=1 时两端各录一份 JFR 到结果目录。
# 退出码：0 全部跑完；1 运行出错。
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/rpc-bench-e2e"
# shellcheck source=lib-bench.sh
. "$ROOT/bench/bin/lib-bench.sh"

CONCURRENCY="64,128,256"
CONNECTIONS=""
CARDS=""
PROFILES="$ROOT/bench/profiles"
SUFFIX="v1"
OUT=""
LABEL="sweep"
while [ $# -gt 0 ]; do
  case "$1" in
    --concurrency) CONCURRENCY="${2:-}"; shift 2 ;;
    --connections) CONNECTIONS="${2:-}"; shift 2 ;;
    --cards) CARDS="${2:-}"; shift 2 ;;
    --profiles) PROFILES="${2:-}"; shift 2 ;;
    --suffix) SUFFIX="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --label) LABEL="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1 (usage: sweep.sh [--concurrency 64,128,256 | --connections 2,4,8 | --cards <id,id>] [--profiles <dir>] [--suffix v1] [--out <dir>] [--label <text>])" >&2; exit 1 ;;
  esac
done
if [ ! -d "$MODULE/target/lib" ]; then
  echo "rpc-bench-e2e is not built; run: mvn -pl rpc-bench-e2e -am package -DskipTests" >&2
  exit 1
fi
OUT="${OUT:-$ROOT/target/sweep/run-$(date +%Y%m%d-%H%M%S)}"
case "$OUT" in /*) ;; *) OUT="$ROOT/$OUT" ;; esac
mkdir -p "$OUT"
export BENCH_JFR_DIR="${BENCH_JFR_DIR:-$OUT}"
trap stop_server EXIT INT TERM

card_for() { # 64 与对比基线共用一张卡：compare-closed64-rpc-<s>
  if [ "$1" = 64 ]; then echo "$PROFILES/compare-closed64-rpc-$SUFFIX.yaml"; else echo "$PROFILES/sweep-closed$1-rpc-$SUFFIX.yaml"; fi
}
if [ -n "$CONNECTIONS" ]; then
  # 连接数扫描：并发 128；2 条（缺省）与并发扫描共用 sweep-closed128-rpc
  card_for() {
    if [ "$1" = 2 ]; then echo "$PROFILES/sweep-closed128-rpc-$SUFFIX.yaml"; else echo "$PROFILES/sweep-c$1-closed128-rpc-$SUFFIX.yaml"; fi
  }
  CONCURRENCY="$CONNECTIONS"
fi
if [ -n "$CARDS" ]; then
  # 指定卡片：每一项就是卡片 id
  card_for() { echo "$PROFILES/$1.yaml"; }
  CONCURRENCY="$CARDS"
fi
IFS=',' read -r -a LEVELS <<< "$CONCURRENCY"
for n in "${LEVELS[@]}"; do
  [ -f "$(card_for "$n")" ] || { echo "card not found: $(card_for "$n")" >&2; exit 1; }
done
if [ -n "$CARDS" ]; then echo "sweep: cards ${CARDS}, label $LABEL -> $OUT"
elif [ -n "$CONNECTIONS" ]; then echo "sweep: connections per node ${CONNECTIONS} at concurrency 128, label $LABEL -> $OUT"
else echo "sweep: concurrency ${CONCURRENCY}, label $LABEL -> $OUT"; fi
# 服务端执行器取自卡片的 target.server_executor（各卡必须一致）
EXECUTOR=""
for n in "${LEVELS[@]}"; do
  e="$(card_server_executor "$(card_for "$n")")"
  if [ -n "$EXECUTOR" ] && [ "$e" != "$EXECUTOR" ]; then
    echo "the cards declare different target.server_executor ($EXECUTOR vs $e); run them separately" >&2
    exit 1
  fi
  EXECUTOR="$e"
done
# 服务端业务等待与排队上限同样取自卡片（各卡必须一致：同一个服务端进程只有一种配置）
SERVER_OPTS=""
for n in "${LEVELS[@]}"; do
  o="$(card_server_options "$(card_for "$n")")"
  if [ -n "$SERVER_OPTS" ] && [ "$o" != "$SERVER_OPTS" ]; then
    echo "the cards declare different server options ($SERVER_OPTS vs $o); run them separately" >&2
    exit 1
  fi
  SERVER_OPTS="$o"
done

PORT=19300
# shellcheck disable=SC2206
SERVER_ARGS=(${SWEEP_SERVER_ARGS:-})
if [ "$EXECUTOR" != fixed ]; then SERVER_ARGS=(--executor "$EXECUTOR" "${SERVER_ARGS[@]}"); fi
# shellcheck disable=SC2206
if [ "$SERVER_OPTS" != "-" ]; then SERVER_ARGS=($SERVER_OPTS "${SERVER_ARGS[@]}"); fi
start_server rpc "$OUT/server-rpc.log" "" --port "$PORT" --host 127.0.0.1 --no-registry "${SERVER_ARGS[@]}"
for n in "${LEVELS[@]}"; do
  echo "== rpc / $(basename "$(card_for "$n")" .yaml)"
  run_card "$(card_for "$n")" "$PORT" "$OUT" "$LABEL" ""
done
stop_server

"$JAVA_HOME/bin/java" -cp "$MODULE/target/classes:$MODULE/target/lib/*" com.feynman.rpc.bench.Bench compare --in "$OUT" --out "$OUT/compare.md"
echo "compare table: $OUT/compare.md"
