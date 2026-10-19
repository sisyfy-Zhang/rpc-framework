#!/usr/bin/env bash
# 按口径卡压测，写 JSON + Markdown（默认 bench/reports/<yyyyMMdd>-<sha7>/）。先构建并起好 bench-server.sh。
#   bench/bin/bench-run.sh --profile bench/profiles/loopback-throughput-v1.yaml --direct 127.0.0.1:9000
#   bench/bin/bench-run.sh --profile bench/profiles/loopback-latency-v1.yaml --registry zk://127.0.0.1:2181 --label nightly
# 退出码：0 通过；1 用法 / 口径卡 / 运行异常；2 实验判定未通过（断言失败或有无效轮次）。
# 环境变量：BENCH_CLIENT_JAVA_OPTS 覆盖 JVM 参数（原样记进报告的 environment.jvm_args）；
#           BENCH_CLIENT_CPUS=<list>（如 5-9）且有 taskset 时绑核启动（报告的 environment.cpus_allowed 记录实际结果）；
#           BENCH_GC_LOG=1 时追加 -Xlog:gc*:file=<dir>/gc-client-<yyyyMMdd-HHmmss>.log，<dir> 为 --out 的值，
#           没有 --out 时为 BENCH_GC_LOG_DIR（缺省 <仓库>/target/gc-logs）。*.log 已被 .gitignore 忽略，不会随归档入库。
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/rpc-bench-e2e"
if [ ! -d "$MODULE/target/lib" ]; then
  echo "rpc-bench-e2e is not built; run: mvn -pl rpc-bench-e2e -am package -DskipTests" >&2
  exit 1
fi
JAVA_OPTS="${BENCH_CLIENT_JAVA_OPTS:--Xms1g -Xmx1g -XX:+AlwaysPreTouch}"
GC_LOG=()
if [ "${BENCH_GC_LOG:-0}" = "1" ]; then
  GC_LOG_DIR="${BENCH_GC_LOG_DIR:-$ROOT/target/gc-logs}"
  prev=""
  for arg in "$@"; do
    if [ "$prev" = "--out" ]; then GC_LOG_DIR="$arg"; fi
    case "$arg" in --out=*) GC_LOG_DIR="${arg#--out=}" ;; esac
    prev="$arg"
  done
  case "$GC_LOG_DIR" in /*) ;; *) GC_LOG_DIR="$ROOT/$GC_LOG_DIR" ;; esac
  mkdir -p "$GC_LOG_DIR"
  # 数组元素原样传给 java：gc* 不会被 shell 当通配符展开
  GC_LOG=("-Xlog:gc*:file=$GC_LOG_DIR/gc-client-$(date +%Y%m%d-%H%M%S).log")
fi
PIN=()
if [ -n "${BENCH_CLIENT_CPUS:-}" ]; then
  if command -v taskset >/dev/null 2>&1; then
    PIN=(taskset -c "$BENCH_CLIENT_CPUS")
  else
    echo "warning: BENCH_CLIENT_CPUS is set but taskset is not available; starting unpinned" >&2
  fi
fi
# 在仓库根目录运行：git sha / dirty 的采集与默认归档目录都相对仓库根
cd "$ROOT"
# shellcheck disable=SC2086
exec "${PIN[@]}" "$JAVA_HOME/bin/java" $JAVA_OPTS "${GC_LOG[@]}" -cp "$MODULE/target/classes:$MODULE/target/lib/*" com.feynman.rpc.bench.Bench run "$@"
