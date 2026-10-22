#!/usr/bin/env bash
# 对比对象并排压测（Linux / WSL2）：依次起各 transport 的服务端（绑 BENCH_SERVER_CPUS，缺省 0-4）→ 跑它的对比卡
# （绑 BENCH_CLIENT_CPUS，缺省 5-9，--direct 直连：基础性能对比排除注册中心变量）→ 停服务端；全部结果写进同一目录，
# 最后用 rpc-bench-e2e compare 生成并排的对比表 compare.md。口径见 docs/benchmarking.md「对比对象」。
#   bench/bin/compare.sh                                   # 开箱即用 + 连接数对齐：rpc、grpc、grpc-c2、dubbo、dubbo-c2 的 closed64 + open10k
#   bench/bin/compare.sh --transports rpc,grpc --loads closed64 --out bench/reports/<yyyyMMdd>-<sha7>/compare
#   bench/bin/compare.sh --profiles ~/t11/profiles --suffix short --label smoke   # 缩短卡（不入库）冒烟流水线
#
# 选项：--transports <a,b,...>（缺省 rpc,grpc,grpc-c2,dubbo,dubbo-c2）：每一项是卡片名里的 transport 段，"-c<N>" 后缀表示
#       "连接数对齐"的变体卡（target.connections: N），"-direct" 后缀表示服务端执行器 direct 的变体卡（rpc-direct，非缺省配置）；
#       服务端按第一个 "-" 之前的 transport 起。rpc 的服务端执行器取自卡片的 target.server_executor（同一 transport 的各张卡必须一致，
#       不是 fixed 时给 bench server 传 --executor <它>）；--loads <closed64,open10k>（缺省两者）；
#       --profiles <dir>（缺省 bench/profiles）与 --suffix <s>（缺省 v1）：卡片文件 = <dir>/compare-<load>-<transport>-<s>.yaml；
#       --out <dir>（缺省 target/compare/run-<时间>，已被 .gitignore 忽略；归档时传该 sha 的归档目录下的子目录）；
#       --label <text>（缺省 compare，透传给 run --label）。
# 环境变量：BENCH_SERVER_CPUS / BENCH_CLIENT_CPUS（缺省 0-4 / 5-9）；COMPARE_SERVER_ARGS_<TRANSPORT 大写>（追加给该 transport
#           服务端的参数，例如 COMPARE_SERVER_ARGS_GRPC="--executor fixed"、COMPARE_SERVER_ARGS_RPC="--flush-consolidation off"）；
#           BENCH_HARNESS_OPTS 等 bench-run.sh 的变量照常生效；BENCH_JFR=1 时两端各录一份 JFR 到结果目录（BENCH_JFR_DIR 缺省 = --out）；
#           BENCH_CONTROL_HTTP=off 时 rpc 服务端不开 HTTP 控制端点，统计照旧经 RPC。
# 服务端统计：rpc 服务端另开 --http-port <端口 + 100>（HTTP 控制端点），脚本写 <out>/control-topology-<transport>.txt 并给它的 run 传 --topology，
#           窗口起止的服务端统计经 GET /stats 取（IO 线程回答、不经业务执行器）；对比对象的服务端没有这个端点，照旧经各自的统计调用。
# 端口：第 i 个 transport 用 19300 + i（rpc 19300、grpc 19301……）。对比模块须已构建：
#   mvn -pl rpc-bench-e2e,rpc-bench-compare-grpc,rpc-bench-compare-dubbo -am package -DskipTests
# 退出码：0 全部跑完（单张卡判定未通过也算跑完，判定写在对比表里）；1 运行出错。
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/rpc-bench-e2e"
# shellcheck source=lib-bench.sh
. "$ROOT/bench/bin/lib-bench.sh"

TRANSPORTS="rpc,grpc,grpc-c2,dubbo,dubbo-c2"
LOADS="closed64,open10k"
PROFILES="$ROOT/bench/profiles"
SUFFIX="v1"
OUT=""
LABEL="compare"
while [ $# -gt 0 ]; do
  case "$1" in
    --transports) TRANSPORTS="${2:-}"; shift 2 ;;
    --loads) LOADS="${2:-}"; shift 2 ;;
    --profiles) PROFILES="${2:-}"; shift 2 ;;
    --suffix) SUFFIX="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --label) LABEL="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1 (usage: compare.sh [--transports rpc,grpc] [--loads closed64,open10k] [--profiles <dir>] [--suffix v1] [--out <dir>] [--label <text>])" >&2; exit 1 ;;
  esac
done
if [ ! -d "$MODULE/target/lib" ]; then
  echo "rpc-bench-e2e is not built; run: mvn -pl rpc-bench-e2e -am package -DskipTests" >&2
  exit 1
fi
OUT="${OUT:-$ROOT/target/compare/run-$(date +%Y%m%d-%H%M%S)}"
case "$OUT" in /*) ;; *) OUT="$ROOT/$OUT" ;; esac
mkdir -p "$OUT"
export BENCH_JFR_DIR="${BENCH_JFR_DIR:-$OUT}"
trap stop_server EXIT INT TERM

# 先检查全部卡片与模块都在，再开始（不要跑了一半才发现少一张卡）
IFS=',' read -r -a TRANSPORT_LIST <<< "$TRANSPORTS"
IFS=',' read -r -a LOAD_LIST <<< "$LOADS"
base_of() { # grpc-c2 -> grpc、rpc-direct -> rpc（卡片变体后缀不影响 classpath；执行器取自卡片）
  echo "${1%%-*}"
}
executor_of() { # 一个 rpc transport 的各张卡声明的服务端执行器（必须一致）
  local t="$1" e="" v l
  for l in "${LOAD_LIST[@]}"; do
    v="$(card_server_executor "$PROFILES/compare-$l-$t-$SUFFIX.yaml")"
    if [ -n "$e" ] && [ "$v" != "$e" ]; then
      echo "cards of transport $t declare different target.server_executor ($e vs $v); run them separately" >&2
      return 1
    fi
    e="$v"
  done
  echo "$e"
}
for t in "${TRANSPORT_LIST[@]}"; do
  extra_cp_for "$(base_of "$t")" > /dev/null
  for l in "${LOAD_LIST[@]}"; do
    card="$PROFILES/compare-$l-$t-$SUFFIX.yaml"
    [ -f "$card" ] || { echo "card not found: $card" >&2; exit 1; }
  done
  if [ "$(base_of "$t")" = rpc ]; then executor_of "$t" > /dev/null; fi
done
echo "compare: transports ${TRANSPORTS}, loads ${LOADS}, cards $PROFILES/compare-<load>-<transport>-$SUFFIX.yaml, label $LABEL -> $OUT"

i=0
for t in "${TRANSPORT_LIST[@]}"; do
  port=$((19300 + i))
  i=$((i + 1))
  base="$(base_of "$t")"
  extra="$(extra_cp_for "$base")"
  var="COMPARE_SERVER_ARGS_$(echo "$base" | tr '[:lower:]-' '[:upper:]_')"
  # shellcheck disable=SC2206
  server_args=(${!var:-})
  topology=""
  if [ "$base" = rpc ]; then
    executor="$(executor_of "$t")"
    if [ "$executor" != fixed ]; then server_args=(--executor "$executor" "${server_args[@]}"); fi
    if control_http_enabled; then server_args+=(--http-port "$((port + 100))"); fi
    start_server "$t" "$OUT/server-$t.log" "" --port "$port" --host 127.0.0.1 --no-registry "${server_args[@]}"
    if control_http_enabled; then
      topology="$OUT/control-topology-$t.txt"
      write_control_topology "$topology" "$port" "$((port + 100))"
    fi
  else
    start_server "$t" "$OUT/server-$t.log" "$extra" --transport "$base" --port "$port" --host 127.0.0.1 "${server_args[@]}"
  fi
  for l in "${LOAD_LIST[@]}"; do
    echo "== $t / $l"
    run_card "$PROFILES/compare-$l-$t-$SUFFIX.yaml" "$port" "$OUT" "$LABEL" "$extra" "$topology"
  done
  stop_server
done

"$JAVA_HOME/bin/java" -cp "$MODULE/target/classes:$MODULE/target/lib/*" com.feynman.rpc.bench.Bench compare --in "$OUT" --out "$OUT/compare.md"
echo "compare table: $OUT/compare.md"
