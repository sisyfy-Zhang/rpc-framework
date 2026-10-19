#!/usr/bin/env bash
# 停掉并删除证据基础设施的容器、网络与数据卷（Prometheus / Grafana 的数据随之清空）。
# 只想停不想删数据时用：docker compose -f docker/compose.yaml down（不带 -v）。
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
docker compose -f "$DIR/compose.yaml" down -v --remove-orphans
left="$(docker ps -a --filter label=com.docker.compose.project=rpc-evidence --format '{{.Names}}')"
if [ -n "$left" ]; then
  echo "containers still present: $left" >&2
  exit 1
fi
echo "down: no rpc-evidence containers left"
