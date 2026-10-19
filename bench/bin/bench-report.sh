#!/usr/bin/env bash
# 把目录里的全部结果 JSON 汇总成一份 Markdown（每张口径卡一节）。
#   bench/bin/bench-report.sh --in bench/reports/20261019-abc1234 --out bench/reports/20261019-abc1234/README.md
# 不传 --out 时打印到标准输出。BENCH_CLIENT_CPUS 同 bench-run.sh（与压测端同一组核，不打扰仍在运行的服务端）。
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/rpc-bench-e2e"
if [ ! -d "$MODULE/target/lib" ]; then
  echo "rpc-bench-e2e is not built; run: mvn -pl rpc-bench-e2e -am package -DskipTests" >&2
  exit 1
fi
PIN=()
if [ -n "${BENCH_CLIENT_CPUS:-}" ]; then
  if command -v taskset >/dev/null 2>&1; then
    PIN=(taskset -c "$BENCH_CLIENT_CPUS")
  else
    echo "warning: BENCH_CLIENT_CPUS is set but taskset is not available; starting unpinned" >&2
  fi
fi
exec "${PIN[@]}" "$JAVA_HOME/bin/java" -cp "$MODULE/target/classes:$MODULE/target/lib/*" com.feynman.rpc.bench.Bench report "$@"
