#!/usr/bin/env bash
# 故障注入实验的多提供者拓扑（Linux / WSL2）：起 N 个 bench server（不同端口，经 ZooKeeper 注册），可选让其中几个"躲在" toxiproxy 后面——
# 提供者监听真实端口，注册中心里写代理端口（server --advertise-port），消费端经代理访问它，驱动脚本在代理上加 / 去 toxic。
#   bench/bin/fault-topology.sh up --providers 3 --out <dir> [--behind-toxiproxy 2 | 1,2,3 | all | none] [--base-port 19401]
#                                  [--registry zk://127.0.0.1:2181] [--server-args "--executor-queue 100000 --work 2ms"]
#   bench/bin/fault-topology.sh down --out <dir>
#   bench/bin/fault-topology.sh wait-zk-clean [--timeout 60]   # 等 ZooKeeper 里不再有 BenchService 的提供者（上一次 kill -9 留下的临时节点随会话超时消失）
#
# up 写 <dir>/topology.txt（每行：编号 公布地址 pid 监听端口 代理名|- HTTP控制端口），各服务端日志 <dir>/server-<编号>.log；全部就绪
# （经代理的还要确认代理已能连到它）后返回。每个服务端另开 --http-port <监听端口 + 100>：harness 经它的 GET /counters 每秒取服务端计数
# （IO 线程回答，不经业务执行器——执行器排满时经 RPC 取会排在压测请求后面、甚至被拒）。
# 拓扑文件交给 harness（run --topology）与 fault-run.sh：故障的 target 就是这里的编号。
# toxiproxy 缺省用 docker/compose.yaml 里的实例（管理 API 127.0.0.1:8474，代理端口段 20000-20010，容器经 host.docker.internal 回连 WSL）；
# 环境变量 TOXIPROXY_API / TOXIPROXY_UPSTREAM_HOST / TOXIPROXY_LISTEN_HOST / TOXIPROXY_PORT_BASE 可改（例如同一镜像的二进制原生跑在 WSL 里：
# TOXIPROXY_API=http://127.0.0.1:8475 TOXIPROXY_UPSTREAM_HOST=127.0.0.1 TOXIPROXY_LISTEN_HOST=127.0.0.1）。
# 服务端绑核 BENCH_SERVER_CPUS（缺省 0-4，N 个服务端共用这组核）；JVM 参数 BENCH_SERVER_JAVA_OPTS（缺省 -Xms1g -Xmx1g -XX:+AlwaysPreTouch
# -Djava.net.preferIPv4Stack=true：Docker Desktop 容器经 host.docker.internal 回连 WSL 时只连得上 IPv4 通配监听，JVM 缺省的 IPv6 双栈通配监听
# 会被拒绝——2026-10-21 冒烟实测；为对称，拓扑里的全部提供者都这样起）。
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOXIPROXY_API="${TOXIPROXY_API:-http://127.0.0.1:8474}"
TOXIPROXY_UPSTREAM_HOST="${TOXIPROXY_UPSTREAM_HOST:-host.docker.internal}"
TOXIPROXY_LISTEN_HOST="${TOXIPROXY_LISTEN_HOST:-0.0.0.0}"
TOXIPROXY_PORT_BASE="${TOXIPROXY_PORT_BASE:-20000}"
ZK_CONTAINER="${ZK_CONTAINER:-rpc-evidence-zookeeper-1}"

usage() {
  sed -n '2,15p' "$0" >&2
  exit 1
}

proxy_name() { echo "rpc-fault-p$1"; }

# 当前 ZooKeeper 里 BenchService 的提供者数（读不到时打印 ?）
zk_provider_count() {
  local out
  out="$(docker exec "$ZK_CONTAINER" zkCli.sh -server 127.0.0.1:2181 ls -R /rpc 2>/dev/null || true)"
  if [ -z "$out" ]; then echo "?"; return 0; fi
  echo "$out" | grep -c 'BenchService.*/providers/.' || true
}

cmd="${1:-}"
[ -n "$cmd" ] || usage
shift
PROVIDERS=""
OUT=""
BEHIND="none"
BASE_PORT=19401
REGISTRY="zk://127.0.0.1:2181"
SERVER_ARGS=""
TIMEOUT=60
while [ $# -gt 0 ]; do
  case "$1" in
    --providers) PROVIDERS="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --behind-toxiproxy) BEHIND="$2"; shift 2 ;;
    --base-port) BASE_PORT="$2"; shift 2 ;;
    --registry) REGISTRY="$2"; shift 2 ;;
    --server-args) SERVER_ARGS="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

behind() { # behind <编号>：该提供者是否经 toxiproxy
  case ",$BEHIND," in
    *,all,*) return 0 ;;
    *,"$1",*) return 0 ;;
    *) return 1 ;;
  esac
}

case "$cmd" in
  wait-zk-clean)
    for _ in $(seq 1 "$TIMEOUT"); do
      n="$(zk_provider_count)"
      if [ "$n" = "0" ]; then echo "zookeeper: no BenchService providers"; exit 0; fi
      if [ "$n" = "?" ]; then echo "warning: cannot read ZooKeeper through docker exec $ZK_CONTAINER; not waiting" >&2; exit 0; fi
      sleep 1
    done
    echo "zookeeper still lists $(zk_provider_count) BenchService provider(s) after ${TIMEOUT}s" >&2
    exit 1
    ;;
  up)
    [ -n "$PROVIDERS" ] && [ -n "$OUT" ] || usage
    if [ ! -d "$ROOT/rpc-bench-e2e/target/lib" ]; then
      echo "rpc-bench-e2e is not built; run: mvn -pl rpc-bench-e2e -am package -DskipTests" >&2
      exit 1
    fi
    mkdir -p "$OUT"
    TOPO="$OUT/topology.txt"
    echo "# idx advertised pid listen_port proxy control_http_port   (fault-topology.sh up, $(date -Iseconds))" > "$TOPO"
    # shellcheck disable=SC2206
    EXTRA=($SERVER_ARGS)
    for i in $(seq 1 "$PROVIDERS"); do
      port=$((BASE_PORT + i - 1))
      advertised="127.0.0.1:$port"
      proxy="-"
      control=$((port + 100))
      args=(--port "$port" --host 127.0.0.1 --registry "$REGISTRY" --http-port "$control")
      if behind "$i"; then
        proxy="$(proxy_name "$i")"
        listen=$((TOXIPROXY_PORT_BASE + i))
        curl -fsS -X DELETE "$TOXIPROXY_API/proxies/$proxy" >/dev/null 2>&1 || true
        curl -fsS -X POST "$TOXIPROXY_API/proxies" \
          -d "{\"name\":\"$proxy\",\"listen\":\"$TOXIPROXY_LISTEN_HOST:$listen\",\"upstream\":\"$TOXIPROXY_UPSTREAM_HOST:$port\",\"enabled\":true}" >/dev/null
        advertised="127.0.0.1:$listen"
        args+=(--advertise-port "$listen")
      fi
      log="$OUT/server-$i.log"
      BENCH_SERVER_JAVA_OPTS="${BENCH_SERVER_JAVA_OPTS:--Xms1g -Xmx1g -XX:+AlwaysPreTouch -Djava.net.preferIPv4Stack=true}"         BENCH_SERVER_CPUS="${BENCH_SERVER_CPUS:-0-4}" setsid nohup "$ROOT/bench/bin/bench-server.sh" "${args[@]}" "${EXTRA[@]}" > "$log" 2>&1 < /dev/null &
      pid=$!
      echo "$i $advertised $pid $port $proxy $control" >> "$TOPO"
    done
    # 等全部就绪（注册完成才打印 ready 行）
    for i in $(seq 1 "$PROVIDERS"); do
      log="$OUT/server-$i.log"
      pid="$(awk -v i="$i" '$1==i {print $3}' "$TOPO")"
      for _ in $(seq 1 120); do
        grep -q "bench-server ready" "$log" && break
        if ! kill -0 "$pid" 2>/dev/null; then echo "provider $i exited before it was ready:" >&2; cat "$log" >&2; exit 1; fi
        sleep 0.5
      done
      grep -q "bench-server ready" "$log" || { echo "provider $i not ready after 60s" >&2; cat "$log" >&2; exit 1; }
      # 经代理的：代理到提供者的上游真正连得上才算就绪（WSL 的端口转发要过几秒才发现新监听；上游连不上时代理接受连接后立刻关掉）
      advertised="$(awk -v i="$i" '$1==i {print $2}' "$TOPO")"
      if [ "$(awk -v i="$i" '$1==i {print $5}' "$TOPO")" != "-" ]; then
        ok=0
        for _ in $(seq 1 60); do
          if python3 -c 'import socket,sys
h,p=sys.argv[1].rsplit(":",1); s=socket.create_connection((h,int(p)),timeout=2); s.settimeout(0.5)
try:
    sys.exit(1 if s.recv(1)==b"" else 0)
except socket.timeout:
    sys.exit(0)' "$advertised" 2>/dev/null; then ok=1; break; fi
          sleep 0.5
        done
        [ "$ok" = 1 ] || { echo "provider $i is not reachable through toxiproxy at $advertised after 30s" >&2; exit 1; }
      fi
    done
    cat "$TOPO"
    ;;
  down)
    [ -n "$OUT" ] || usage
    TOPO="$OUT/topology.txt"
    [ -f "$TOPO" ] || { echo "no topology at $TOPO" >&2; exit 0; }
    while read -r idx advertised pid port proxy _; do
      case "$idx" in '#'*|'') continue ;; esac
      if kill -0 "$pid" 2>/dev/null; then
        kill -CONT "$pid" 2>/dev/null || true   # sigstop 的提供者先恢复，才能优雅关闭
        kill -TERM "$pid" 2>/dev/null || true
      fi
      if [ "$proxy" != "-" ]; then
        curl -fsS -X DELETE "$TOXIPROXY_API/proxies/$proxy" >/dev/null 2>&1 || true
      fi
    done < "$TOPO"
    while read -r idx advertised pid port proxy _; do
      case "$idx" in '#'*|'') continue ;; esac
      for _ in $(seq 1 150); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
      if kill -0 "$pid" 2>/dev/null; then echo "warning: provider $idx ($pid) did not stop after SIGTERM; killing it" >&2; kill -KILL "$pid" 2>/dev/null || true; fi
    done < "$TOPO"
    echo "topology down: $TOPO"
    ;;
  *) usage ;;
esac
