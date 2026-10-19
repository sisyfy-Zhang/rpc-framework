-- wrk / wrk2 请求脚本：POST 固定 1024 字节请求体（与 crossval-http-open-v1 / crossval-http-closed-capacity-v1 口径卡的 payload.size 一致）。
--   wrk  -t 4 -c 64 -d 40s --latency -s bench/bin/post-1k.lua http://127.0.0.1:19180/echo
--   wrk2 -t 4 -c 64 -d 40s -R 5000 --latency -s bench/bin/post-1k.lua http://127.0.0.1:19180/echo
-- Content-Length 由 wrk 按 body 自动计算；内容是固定的 1024 个 'x'（回显开销与内容无关）。
wrk.method = "POST"
wrk.body = string.rep("x", 1024)
wrk.headers["Content-Type"] = "application/octet-stream"
