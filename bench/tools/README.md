# 交叉验证用的第三方发压工具：wrk / wrk2

`bench/bin/crossval.sh` 用 wrk（闭环容量标定）与 wrk2（开环测量校验）打与 harness 同一个 HTTP 目标（见
[docs/benchmarking.md「交叉验证」](../../docs/benchmarking.md#交叉验证)）。两者都从源码编译到用户目录（不需要 sudo），并打本目录里的补丁。
脚本缺省从 `~/tools/bin` 找它们（`CROSSVAL_TOOLS_BIN` 可改）。

| 工具 | 上游 | 验证过的提交 | 补丁 |
|---|---|---|---|
| wrk 4.2.0 | https://github.com/wg/wrk | `a211dd5a7050b1f9e8a9870b95513060e72ac4a0` | `wrk-monotonic-clock.patch` |
| wrk2 4.0.0 | https://github.com/giltene/wrk2 | `44a94c17d8e6a0bac8559b53da76848e430cb7a7` | `wrk2-build-and-clock.patch` |

验证环境：WSL2 Ubuntu（gcc 15.2、OpenSSL 3.5、binutils 新版），需要 `build-essential`、`libssl-dev`、`zlib1g-dev`、`git`。

## 编译

在仓库根目录执行（`$REPO` = 仓库根的绝对路径）：

```bash
REPO=$(pwd)
mkdir -p ~/tools/src ~/tools/bin

# wrk
git clone https://github.com/wg/wrk ~/tools/src/wrk
cd ~/tools/src/wrk
git checkout a211dd5a7050b1f9e8a9870b95513060e72ac4a0
git apply "$REPO/bench/tools/wrk-monotonic-clock.patch"
# 自带的 LuaJIT 是 zip；没有 unzip 时用 python 解开（Makefile 发现 obj/LuaJIT-2.1 已存在就不再解压）
mkdir -p obj && python3 -m zipfile -e deps/LuaJIT-2.1.zip obj/ && touch obj/LuaJIT-2.1
make -j WITH_OPENSSL=/usr          # 用系统 OpenSSL 3，不编译自带的 openssl-1.1.1i
cp wrk ~/tools/bin/wrk

# wrk2
git clone https://github.com/giltene/wrk2 ~/tools/src/wrk2
cd ~/tools/src/wrk2
git checkout 44a94c17d8e6a0bac8559b53da76848e430cb7a7
git apply "$REPO/bench/tools/wrk2-build-and-clock.patch"
make -j
cp wrk ~/tools/bin/wrk2

~/tools/bin/wrk -v | head -1       # wrk 4.2.0 [epoll] ...
~/tools/bin/wrk2 -v | head -1      # wrk 4.0.0 [epoll] ...（wrk2 沿用 wrk 的版本号）
```

## 补丁说明

**两个工具的 `time_us()`：`gettimeofday` → `clock_gettime(CLOCK_MONOTONIC)`**（两份补丁都含）。
两者都用墙上时钟计时，WSL2 与宿主同步时间时墙上时钟会回拨（本机 45s 内观测到 2 次）：wrk2 因此记下负的延迟，在
`hdr_histogram.c:54 counts_index: Assertion 'bucket_index < h->bucket_count' failed` 处 abort；wrk 则把回拨时刻在途的请求
（`now - start` 下溢成巨大的无符号数）记成 `timeout` 错误（一次 10s 运行里 40 个假 timeout，而最大延迟只有 108ms）。
只换计时源，不改任何测量逻辑。`src/ae.c` 的定时器（`aeGetTime`）仍用墙上时钟，未改。

**wrk2 的 Makefile：内嵌 Lua 字节码改为经 C 文件编译**。原规则 `luajit -b src/wrk.lua obj/bytecode.o` 直接产出 ELF 目标文件，
新版 binutils 链接时报 `obj/bytecode.o: string table [3] is corrupt`，链接"成功"但运行时 `attempt to index global 'wrk'
(a nil value)`（脚本没被正确嵌入）。改为先 `luajit -b src/wrk.lua obj/bytecode.c`、再用 `$(CC) $(CFLAGS) -c` 编译，
与 wrk 4.2 的做法相同。

## 已知局限（不是补丁能解决的）

wrk2 的发送节拍是**毫秒粒度**：`src/wrk.c` 第 604 行 `msec_to_wait = round((time_usec_to_wait / 1000.0L) + 0.5)` 把等待向上取整到
整毫秒、交给毫秒粒度的 ae 定时器，每个请求都比计划时刻晚发零点几到一毫秒多，并计入它的"修正后"延迟。所以亚毫秒级目标上，
开环交叉验证比较的是 harness 的服务时间与 wrk2 `-U` 的"未修正"延迟，wrk2 的修正后延迟只作信息项。
