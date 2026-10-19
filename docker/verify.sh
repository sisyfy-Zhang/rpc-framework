#!/usr/bin/env bash
# 起证据基础设施并逐个探测健康端点：docker compose up -d --wait 之后，从宿主机再探一遍五个服务，打印表格。
# 任何一个探测失败退出码非零。用法：bash docker/verify.sh（Linux / WSL / Git Bash 均可；需要 docker 与 curl）。
# 环境变量：VERIFY_HOST（缺省 127.0.0.1）——从另一台机器或 WSL 探测时改成能到达 Docker 宿主机的地址；
#           VERIFY_SKIP_UP=1 —— 只探测，不执行 compose up。
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST="${VERIFY_HOST:-127.0.0.1}"
COMPOSE=(docker compose -f "$DIR/compose.yaml")

if [ "${VERIFY_SKIP_UP:-0}" != "1" ]; then
  echo "== docker compose up -d --wait"
  if ! "${COMPOSE[@]}" up -d --wait --wait-timeout 300; then
    echo "compose up failed or a service did not become healthy:" >&2
    "${COMPOSE[@]}" ps -a >&2
    exit 1
  fi
fi

FAILED=0
ROWS=()

record() { # service endpoint result detail
  ROWS+=("$1|$2|$3|$4")
  if [ "$3" != "OK" ]; then
    FAILED=1
  fi
}

http_probe() { # service url expected-substring(optional)
  local service="$1" url="$2" expect="${3:-}" body code
  body="$(curl -sS --max-time 10 -w '\n%{http_code}' "$url" 2>&1)"
  code="${body##*$'\n'}"
  body="${body%$'\n'*}"
  body="$(printf '%s' "$body" | tr -d '\r\n' | cut -c1-60)"
  if [ "$code" = "200" ] && { [ -z "$expect" ] || printf '%s' "$body" | grep -q "$expect"; }; then
    record "$service" "$url" "OK" "HTTP $code $body"
  else
    record "$service" "$url" "FAIL" "HTTP ${code:-none} $body"
  fi
}

zk_probe() { # 4lw srvr over a raw TCP socket (bash /dev/tcp; no nc needed on the host)
  local out
  out="$(timeout 10 bash -c "exec 3<>/dev/tcp/$HOST/2181 && printf 'srvr' >&3 && cat <&3" 2>&1)"
  local mode
  mode="$(printf '%s\n' "$out" | grep -m1 '^Mode:' | tr -d '\r')"
  local version
  version="$(printf '%s\n' "$out" | grep -m1 '^Zookeeper version:' | cut -d, -f1 | sed 's/^Zookeeper version: //' | tr -d '\r')"
  if [ -n "$mode" ]; then
    record "zookeeper" "tcp://$HOST:2181 (4lw srvr)" "OK" "$mode, $version"
  else
    record "zookeeper" "tcp://$HOST:2181 (4lw srvr)" "FAIL" "$(printf '%s' "$out" | tr -d '\r\n' | cut -c1-60)"
  fi
}

zk_probe
http_probe "nacos" "http://$HOST:8848/nacos/v1/console/health/readiness"
http_probe "prometheus" "http://$HOST:9090/-/ready" "Ready"
http_probe "grafana" "http://$HOST:3000/api/health" '"database": *"ok"'
http_probe "toxiproxy" "http://$HOST:8474/version"

echo
printf '%-11s | %-56s | %-6s | %s\n' "service" "endpoint" "result" "detail"
printf '%s\n' "------------+----------------------------------------------------------+--------+------------------------------------------"
for row in "${ROWS[@]}"; do
  IFS='|' read -r s e r d <<< "$row"
  printf '%-11s | %-56s | %-6s | %s\n' "$s" "$e" "$r" "$d"
done
echo
"${COMPOSE[@]}" ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}'

if [ "$FAILED" -ne 0 ]; then
  echo "verify: FAILED" >&2
  exit 1
fi
echo "verify: all 5 services healthy"
