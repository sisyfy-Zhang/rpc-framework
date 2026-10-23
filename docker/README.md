# 证据基础设施（docker/）

一条命令起本机开发与压测取证用的中间件：ZooKeeper、Nacos、Prometheus、Grafana、toxiproxy。只用于本机，不是生产部署。

## 起停

在仓库根目录（Windows 用 Git Bash，或 WSL / Linux 的 bash）：

```bash
bash docker/verify.sh   # docker compose up -d --wait，再从宿主机逐个探测五个服务的健康端点并打印表格；任何一个失败退出码非零
bash docker/down.sh     # docker compose down -v：删除容器、网络与数据卷（Prometheus / Grafana 的数据随之清空）
```

只起不探测：`docker compose -f docker/compose.yaml up -d --wait`；只停不删数据：`docker compose -f docker/compose.yaml down`。
compose 的项目名固定为 `rpc-evidence`（容器名 `rpc-evidence-<服务>-1`）。每个服务都有 healthcheck，`--wait` 会等到全部 healthy 才返回。

## 端口

| 服务 | 镜像 | 宿主机端口 | 用途 / 健康检查 |
|---|---|---|---|
| ZooKeeper | `zookeeper:3.9.6` | 2181 | 注册中心；4lw 白名单 `srvr,ruok,stat,mntr`，健康检查 `srvr` |
| Nacos | `nacos/nacos-server:v2.4.3`（standalone） | 8848（HTTP / 控制台 `/nacos`）、9848（gRPC） | 注册中心；健康检查 `/nacos/v1/console/health/readiness` |
| Prometheus | `prom/prometheus:v3.15.0` | 9090 | 抓取宿主机上的 RPC 进程（见下表）；健康检查 `/-/ready` |
| Grafana | `grafana/grafana:12.4.13` | 3000 | 面板 **RPC Overview**（uid `rpc-overview`）；健康检查 `/api/health` |
| toxiproxy | `ghcr.io/shopify/toxiproxy:2.13.0` | 8474（API）、20000-20010（代理端口段） | 故障注入；verify.sh 探测 `/version`（容器内 healthcheck 用 `toxiproxy-cli list`：镜像里没有 shell 与 curl） |

Prometheus 预置的抓取目标（`prometheus/prometheus.yml`，间隔 5s，地址 `host.docker.internal:<端口>`）：

| job | 端口 | 谁来暴露 |
|---|---|---|
| `rpc-bench-server` | 9464 | `bench server ... --metrics-port 9464`（[benchmarking.md](../docs/benchmarking.md)） |
| `rpc-bench-client` | 9465 | `bench run ... --metrics-port 9465` |
| `rpc-example-provider` | 9466 | `PlainProvider localhost:2181 9100 --metrics-port 9466`（[steadyrpc-examples](../steadyrpc-examples/README.md)） |
| `rpc-example-consumer` | 9467 | 预留 |

进程没起来时对应目标在 <http://localhost:9090/targets> 上显示为 down，属正常。指标与面板的说明见 [docs/observability.md](../docs/observability.md)。

## 从 Windows 与 WSL 访问

- **Windows**（Docker Desktop）：浏览器或 Git Bash 里用 `localhost:<端口>`，如 <http://localhost:3000>（Grafana）、<http://localhost:9090>（Prometheus）。
- **WSL2**（NAT 模式 + Docker Desktop 的 WSL 集成）：WSL 里同样用 `localhost:<端口>`，例如 `curl localhost:9090/-/ready`。没有开 WSL 集成时，
  改用 Windows 宿主机地址（WSL 里 `ip route | awk '/default/ {print $3}'`），并用 `VERIFY_HOST=<该地址> bash docker/verify.sh` 探测。
- **被抓取的一侧**：RPC 进程跑在 Windows 上时，Prometheus 经 `host.docker.internal` 直接到达；跑在 WSL2 里时，依赖 WSL 的
  `localhostForwarding`（缺省开启）把 Windows 侧的 `localhost:<端口>` 转进 WSL——进程要监听全部网卡（`PrometheusEndpoint` 缺省如此）。
  以 Prometheus 的 targets 页面为准确认 `up`。
- **Linux 引擎**：compose 给 Prometheus 加了 `extra_hosts: host.docker.internal:host-gateway`，同一份配置可用。

## 账号与安全

- Grafana 开了**匿名只读**（Viewer），免登录看面板；管理员账号 `admin` / `rpc-local-only` 写在 compose 里，**仅供本机**。
- 所有端口按 Docker 缺省发布在宿主机的全部网卡上，内嵌 `/metrics` 端点也没有鉴权：不要在不可信网络上运行这套 compose。
- compose 不挂载宿主机的 Docker socket。

## 目录

```text
docker/
  compose.yaml                                    五个服务、healthcheck、数据卷
  prometheus/prometheus.yml                       抓取配置
  grafana/provisioning/datasources/prometheus.yaml  数据源（uid rpc-prometheus）
  grafana/provisioning/dashboards/dashboards.yaml   从 grafana/dashboards 加载面板
  grafana/dashboards/rpc-overview.json            RPC Overview 面板
  toxiproxy/                                      故障注入场景脚本（预留）
  verify.sh / down.sh                             起并验证 / 清理
```
