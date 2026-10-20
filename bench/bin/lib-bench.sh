# compare.sh / sweep.sh 共用：起停一个压测服务端（bench-server.sh，后台、绑核），等就绪行。由调用方 source。
# 需要调用方先设置 ROOT。用法：
#   start_server <name> <log> <BENCH_EXTRA_CP 或空> <bench-server.sh 的参数...>   # 设置 SERVER_PID
#   stop_server                                                                   # SIGTERM，10s 不退再 KILL
#   extra_cp_for <transport>                                                      # 对比模块的 classpath（rpc 为空）

SERVER_PID=""

extra_cp_for() {
  local transport="$1" module
  if [ "$transport" = rpc ]; then
    echo ""
    return 0
  fi
  module="$ROOT/rpc-bench-compare-$transport"
  if [ ! -d "$module/target/lib" ] || [ ! -d "$module/target/classes" ]; then
    echo "rpc-bench-compare-$transport is not built; run: mvn -pl rpc-bench-e2e,rpc-bench-compare-$transport -am package -DskipTests" >&2
    return 1
  fi
  echo "$module/target/classes:$module/target/lib/*"
}

start_server() {
  local name="$1" log="$2" extra="$3"
  shift 3
  # bench-server.sh 以 exec 启动 java，所以 $! 就是 JVM 的 pid
  BENCH_EXTRA_CP="$extra" BENCH_SERVER_CPUS="${BENCH_SERVER_CPUS:-0-4}" "$ROOT/bench/bin/bench-server.sh" "$@" > "$log" 2>&1 < /dev/null &
  SERVER_PID=$!
  for _ in $(seq 1 120); do
    grep -q "bench-server ready" "$log" && break
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "$name server exited before it was ready:" >&2
      cat "$log" >&2
      SERVER_PID=""
      return 1
    fi
    sleep 0.5
  done
  if ! grep -q "bench-server ready" "$log"; then
    echo "$name server not ready after 60s:" >&2
    cat "$log" >&2
    return 1
  fi
  grep "bench-server ready" "$log"
}

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

# run_card <card.yaml> <port> <out> <label> <BENCH_EXTRA_CP 或空>：退出码 0 / 2 照常返回（2 = 卡片判定未通过，结果照样进对比表），1 = 运行出错
run_card() {
  local card="$1" port="$2" out="$3" label="$4" extra="$5" code
  if [ ! -f "$card" ]; then
    echo "card not found: $card" >&2
    return 1
  fi
  set +e
  BENCH_EXTRA_CP="$extra" BENCH_CLIENT_CPUS="${BENCH_CLIENT_CPUS:-5-9}" "$ROOT/bench/bin/bench-run.sh" --profile "$card" \
    --direct "127.0.0.1:$port" --out "$out" --label "$label"
  code=$?
  set -e
  if [ "$code" -ne 0 ] && [ "$code" -ne 2 ]; then
    echo "run failed (exit $code): $card" >&2
    return 1
  fi
  return 0
}
