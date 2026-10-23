#!/usr/bin/env python3
"""用 OSV（https://osv.dev）核对 Maven 依赖树里每个第三方构件的已知漏洞。

用法（仓库根目录；只用 Python 3 标准库，需要能访问 api.osv.dev）：
    ./mvnw -B dependency:tree > tree.log
    python -I bench/tools/osv_scan.py tree.log [更多 tree.log ...]

输入是 `mvn dependency:tree` 的控制台输出（带 [INFO] 前缀与 "--- dependency:...:tree ... @ <模块> ---" 分隔行），
reactor 树与"使用方视角"的树（docs/releasing.md「依赖漏洞核对」里的消费者 pom）都可以。
`-Dverbose` 输出里标了 "omitted for ..." 的落选版本不在 classpath 上，跳过；本项目自己的模块（io.github.sisyfy-zhang）跳过。

输出：每个命中的 group:artifact:version、OSV 编号与出现的（模块, scope），最后一行是坐标数与命中数。
退出码：0 = 无命中；1 = 有命中；2 = 用法错误或树里没解析出任何坐标。
"""
import json
import re
import sys
import urllib.request

OSV_BATCH = "https://api.osv.dev/v1/querybatch"
MODULE = re.compile(r"--- dependency:[^@]*@ (\S+) ---")
COORD = re.compile(
    r"([\w.\-]+):([\w.\-]+):(?:jar|pom|test-jar|bundle|maven-plugin)(?::([\w.\-]+))?:([\w.\-]+):(compile|runtime|test|provided|system)"
)
OWN_GROUP = "io.github.sisyfy-zhang"


def collect(paths):
    coords = {}
    for path in paths:
        module = None
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                m = MODULE.search(line)
                if m:
                    module = m.group(1)
                    continue
                if "omitted for" in line:
                    continue
                m = COORD.search(line)
                if not m:
                    continue
                group, artifact, _classifier, version, scope = m.groups()
                if group == OWN_GROUP:
                    continue
                coords.setdefault((group, artifact, version), set()).add((module or "?", scope))
    return coords


def query(keys):
    results = []
    for i in range(0, len(keys), 500):
        batch = [{"package": {"name": f"{g}:{a}", "ecosystem": "Maven"}, "version": v} for g, a, v in keys[i:i + 500]]
        req = urllib.request.Request(OSV_BATCH, data=json.dumps({"queries": batch}).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            results += json.load(resp)["results"]
    return results


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    coords = collect(argv[1:])
    if not coords:
        print("no third-party coordinates found; is this dependency:tree output?", file=sys.stderr)
        return 2
    keys = sorted(coords)
    hits = 0
    for key, result in zip(keys, query(keys)):
        ids = sorted(v["id"] for v in result.get("vulns", []))
        if ids:
            hits += len(ids)
            print(":".join(key), ids, sorted(coords[key]))
    print(f"{len(keys)} coordinates, {hits} vulnerability hits")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
