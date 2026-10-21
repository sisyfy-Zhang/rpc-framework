# toxiproxy 场景脚本

故障注入场景的驱动脚本在 `bench/bin/`：`fault-topology.sh` 在 20000-20010 端口段上为指定的提供者建代理（`rpc-fault-p<编号>`，监听 20000 + 编号，
上游 `host.docker.internal:<提供者端口>`），`fault-run.sh` 按 harness 写出的故障时间表经 `http://localhost:8474` 的管理 API 加 / 去 toxic
（latency、timeout）。用法、口径与这条代理路径本身的开销见 [docs/benchmarking.md「故障注入」](../../docs/benchmarking.md#故障注入)。

两条已知限制（2026-10-21 冒烟实测，详见 docs/benchmarking.md「已知局限」）：

- **路径开销**：这个容器在 Docker Desktop 里，代理到 WSL 里的提供者要绕 WSL → Docker Desktop 端口转发 → 容器 → host.docker.internal → Windows → WSL。
  三个提供者都经它、5000 req/s、不加任何 toxic 时，对照就已是 p50 11.5ms / p99 56ms（原生跑在 WSL 里的同版本二进制：p50 0.91ms）。
  所以慢节点等对延迟敏感的正式实验改用原生进程（`TOXIPROXY_API` 等环境变量切换，脚本缺省仍指向这里）。
- **只连得上 IPv4 通配监听**：经 host.docker.internal 回连时，JVM 缺省的 IPv6 双栈通配监听（`::`）被拒绝；提供者要带 `-Djava.net.preferIPv4Stack=true`。
