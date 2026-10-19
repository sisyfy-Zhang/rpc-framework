#!/usr/bin/env bash
# 压测服务端（前台运行；Ctrl-C / SIGTERM 优雅关闭）。先构建：mvn -pl rpc-bench-e2e -am package -DskipTests
#   bench/bin/bench-server.sh --port 9000 --host 127.0.0.1 --no-registry
#   bench/bin/bench-server.sh --port 9000 --host 127.0.0.1 --registry zk://127.0.0.1:2181
# 就绪时打印一行 "bench-server ready port=... pid=..."。
# 环境变量：BENCH_SERVER_JAVA_OPTS 覆盖 JVM 参数；BENCH_SERVER_CPUS=<list>（如 0-4）且有 taskset 时绑核启动。
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODULE="$ROOT/rpc-bench-e2e"
if [ ! -d "$MODULE/target/lib" ]; then
  echo "rpc-bench-e2e is not built; run: mvn -pl rpc-bench-e2e -am package -DskipTests" >&2
  exit 1
fi
JAVA_OPTS="${BENCH_SERVER_JAVA_OPTS:--Xms1g -Xmx1g -XX:+AlwaysPreTouch}"
PIN=()
if [ -n "${BENCH_SERVER_CPUS:-}" ]; then
  if command -v taskset >/dev/null 2>&1; then
    PIN=(taskset -c "$BENCH_SERVER_CPUS")
  else
    echo "warning: BENCH_SERVER_CPUS is set but taskset is not available; starting unpinned" >&2
  fi
fi
cd "$ROOT"
# shellcheck disable=SC2086  # JAVA_OPTS 需要按空格拆成多个参数
exec "${PIN[@]}" "$JAVA_HOME/bin/java" $JAVA_OPTS -cp "$MODULE/target/classes:$MODULE/target/lib/*" com.feynman.rpc.bench.Bench server "$@"
