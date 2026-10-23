#!/usr/bin/env python3
"""从归档的结果 JSON 生成 README 用的对比图与架构图（SVG）。

用法（仓库根目录）：
    python bench/bin/charts.py --reports bench/reports --out docs/images

依赖：Python 3 标准库 + matplotlib（3.8 上验证）。不联网、不跑压测，只读归档 JSON：

  throughput-closed64.svg   bench/reports/20261022-d23e39d/compare/compare-closed64-*.json
                            每个文件取 summary.official.throughput_rps（正式轮 = 3 轮里窗口吞吐的中位那一轮）
  tail-latency-open10k.svg  bench/reports/20261022-d23e39d/compare/compare-open10k-*.json
                            每个文件取 summary.official.p50_ms / p99_ms（正式轮 = 3 轮里窗口 p99 的中位那一轮），
                            另画 summary.spread.p99_ms.min / max（三轮 p99 极差）
  fault-recovery.svg        bench/reports/probes/20261022-task19-outlier-detection/{kill,timeout}/*/*.json
                            每个文件取 summary.official.window_errors（单轮，窗口 [10s, 60s]）与
                            rounds[0].faults[0].recovery.errors_recovery_s（recovered = false 时画"未恢复"）
                            ——探针口径（git_dirty = true），不作数
  architecture.svg          不读数据，按调用路径手工布局（消费端 → 协议 v2 → 服务端，旁路注册中心与指标）

每张数据图另写一份 <图名>.md（与 SVG 同目录）：图里每个数字、来源文件、取数字段与口径；README 引用图时以它为准。
脚本对每个来源文件做一致性检查（卡片 id、git_sha、git_dirty、判定通过、离群摘除开关与目录名一致），不符即退出码 1。
输出是确定的：同一份 JSON 与同一个 matplotlib 版本，重复运行得到逐字节相同的 SVG（固定 svg.hashsalt、不写日期）。

字体：优先 Microsoft YaHei，其次 PingFang SC / Noto Sans CJK SC / Source Han Sans SC / SimHei / WenQuanYi；
都找不到时退回英文标签并在标准错误上提示。文字转成路径写进 SVG（svg.fonttype = path），查看者不需要装中文字体。

matplotlib 的字体缓存缺省写在用户目录（Windows 上在 C 盘）；不想写 C 盘时先把环境变量 MPLCONFIGDIR 指向别处再运行，
例如 Git Bash 里 `MPLCONFIGDIR=G:/rpc-build-tmp/mplconfig python bench/bin/charts.py ...`。脚本本身不设置它。
"""

import argparse
import glob
import json
import os
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402
from matplotlib.patches import FancyBboxPatch, Rectangle, FancyArrowPatch  # noqa: E402
from matplotlib.ticker import FixedLocator, NullLocator  # noqa: E402

# ---------------------------------------------------------------------------
# 归档位置与口径常量（改归档批次只改这里）
# ---------------------------------------------------------------------------
COMPARE_BATCH = "20261022-d23e39d"
COMPARE_SHA = "d23e39d"
PROBE_BATCH = os.path.join("probes", "20261022-task19-outlier-detection")
PROBE_SHA = "f160df9"
PROBE_DOC = "docs/probes/outlier-detection.md"

# 闭环 / 开环两组共用的行：(卡片 id 后缀, 中文标签, 英文标签, 角色)
# 角色：ours = 本框架缺省（强调色）、ours-opt = 本框架非缺省 opt-in（强调色浅一档）、peer = 对比对象（灰）
ROWS = [
    ("rpc-v1", "本框架 fixed（缺省）", "This framework, fixed (default)", "ours"),
    ("rpc-direct-v1", "本框架 direct（opt-in）", "This framework, direct (opt-in)", "ours-opt"),
    ("grpc-v1", "gRPC-Java 开箱即用（1 channel）", "gRPC-Java default (1 channel)", "peer"),
    ("grpc-c2-v1", "gRPC-Java 2 连接", "gRPC-Java, 2 channels", "peer"),
    ("dubbo-v1", "Dubbo triple 开箱即用（1 连接）", "Dubbo triple default (1 conn)", "peer"),
    ("dubbo-c2-v1", "Dubbo triple 2 连接", "Dubbo triple, 2 conns", "peer"),
]
GROUP_BREAKS = {2, 4}  # 在这些行之前留组间空隙：本框架 | gRPC | Dubbo

# ---------------------------------------------------------------------------
# 配色（dataviz 参考调色板，浅色表面；强调 = 一个蓝色，其余为灰）
# 校验：validate_palette.js "#2a78d6,#eb6834" 全部通过；强调式（1 色 + 灰）中的灰按设计不满足色度下限。
# ---------------------------------------------------------------------------
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_2 = "#52514e"
MUTED = "#898781"
GRID = "#e1e0d9"
AXIS = "#c3c2b7"
ACCENT = "#2a78d6"        # 蓝 450：本框架缺省 / 离群摘除开 / p99
ACCENT_LIGHT = "#86b6ef"  # 蓝 250：本框架 opt-in / p50
ACCENT_DARK = "#1c5cab"   # 蓝 550：p99 点
PEER = "#898781"          # 对比对象 / 离群摘除关
PEER_LIGHT = "#d6d5cf"    # "未恢复"的删失条
PANEL = "#f3f2ee"         # 架构图容器底色
BAND = "#e8e7e1"          # 三轮 p99 极差带

PX = 96.0  # 1 英寸 = 96 CSS px（SVG 以 pt 写尺寸，浏览器按 96 px/in 显示）
ZH = True  # 由 setup_fonts() 决定


def T(zh, en):
    return zh if ZH else en


def pt(px):
    """CSS 像素 → matplotlib 字号（pt）。"""
    return px * 72.0 / PX


def setup_fonts():
    global ZH
    wanted = ["Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC",
              "SimHei", "WenQuanYi Micro Hei"]
    have = {f.name for f in font_manager.fontManager.ttflist}
    found = next((w for w in wanted if w in have), None)
    if found is None:
        ZH = False
        print("charts.py: 没有找到中文字体（" + " / ".join(wanted) + "），退回英文标签", file=sys.stderr)
        family = ["DejaVu Sans"]
    else:
        family = [found, "DejaVu Sans"]
    plt.rcParams.update({
        "font.family": "sans-serif",
        "font.sans-serif": family,
        "axes.unicode_minus": False,
        "svg.fonttype": "path",
        "svg.hashsalt": "rpc-framework-charts",
        "figure.facecolor": SURFACE,
        "savefig.facecolor": SURFACE,
        "axes.facecolor": SURFACE,
        "axes.edgecolor": AXIS,
        "axes.linewidth": 0.75,
        "xtick.color": MUTED,
        "ytick.color": MUTED,
        "xtick.labelcolor": INK_2,
        "ytick.labelcolor": INK,
        "text.color": INK,
    })
    return found


# ---------------------------------------------------------------------------
# 读数与检查
# ---------------------------------------------------------------------------
class DataError(Exception):
    pass


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def rel(path, root):
    return os.path.relpath(path, root).replace(os.sep, "/")


def find_compare(reports, load_name, suffix):
    """compare/<卡片 id>-<时间戳>.json，卡片 id = compare-<load>-<suffix>；必须恰好一个。"""
    card = "compare-%s-%s" % (load_name, suffix)
    pattern = os.path.join(reports, COMPARE_BATCH, "compare", card + "-2*.json")
    hits = sorted(glob.glob(pattern))
    if len(hits) != 1:
        raise DataError("%s：期望 1 个结果文件，找到 %d 个（%s）" % (card, len(hits), pattern))
    d = load(hits[0])
    env = d["environment"]
    problems = []
    if d["profile"]["id"] != card:
        problems.append("profile.id = %s" % d["profile"]["id"])
    if not env["git_sha"].startswith(COMPARE_SHA):
        problems.append("git_sha = %s" % env["git_sha"])
    if env["git_dirty"]:
        problems.append("git_dirty = true")
    if not d["validity"]["passed"]:
        problems.append("validity.passed = false")
    if problems:
        raise DataError("%s：%s" % (hits[0], "；".join(problems)))
    return hits[0], d


def compare_conditions(docs):
    """从 JSON 里取测试条件，拼脚注；各文件不一致即报错。"""
    def one(getter, what):
        vals = {getter(d) for d in docs}
        if len(vals) != 1:
            raise DataError("各结果文件的 %s 不一致：%s" % (what, sorted(map(str, vals))))
        return vals.pop()
    cpu = one(lambda d: d["environment"]["cpu_model"], "cpu_model")
    cli = one(lambda d: d["environment"]["cpus_allowed"], "压测端 cpus_allowed")
    srv = one(lambda d: d["server_environment"]["cpus_allowed"], "服务端 cpus_allowed")
    size = one(lambda d: d["profile"]["parsed"]["target"]["payload"]["size"], "payload.size")
    rounds = one(lambda d: d["profile"]["parsed"]["load"]["rounds"], "load.rounds")
    dur = one(lambda d: d["profile"]["parsed"]["load"]["duration"], "load.duration")
    wsl = one(lambda d: d["environment"]["wsl"], "environment.wsl")
    cpu_short = cpu.replace(" with Radeon Graphics", "")
    return T(
        "测试条件：同机%s loopback · %s · 服务端 cpu %s / 压测端 cpu %s（taskset 各 5 核）· %s 回显 · 直连 · 每卡 %d 轮 × %s 取正式轮\n"
        "归档 %s（git_dirty = false）· 同机数字不可外推"
        % (" WSL2" if wsl else "", cpu_short, srv, cli, size_text(size), rounds, dur, COMPARE_SHA),
        "Same-host%s loopback · %s · server cpu %s / load generator cpu %s (5 cores each, taskset) · %s echo · direct · %d rounds × %s, official round\n"
        "archive %s (clean) · same-host numbers do not extrapolate"
        % (" WSL2" if wsl else "", cpu_short, srv, cli, size_text(size), rounds, dur, COMPARE_SHA))


# ---------------------------------------------------------------------------
# 画图辅助
# ---------------------------------------------------------------------------
def new_fig(w_px, h_px):
    fig = plt.figure(figsize=(w_px / PX, h_px / PX))
    fig.patch.set_facecolor(SURFACE)
    # 不透明浅色底 + 发丝边框：GitHub 暗色主题下也是一张清楚的浅色卡片
    fig.patches.append(Rectangle((0, 0), 1, 1, transform=fig.transFigure, fill=False,
                                 edgecolor=AXIS, linewidth=1.0, zorder=-10))
    return fig


def add_axes_px(fig, w_px, h_px, left, top, width, height):
    """按像素（左上原点）放一个坐标轴，布局完全确定。"""
    return fig.add_axes([left / w_px, 1 - (top + height) / h_px, width / w_px, height / h_px])


def fig_text(fig, w_px, h_px, x, y, s, size, color=INK, weight="normal", ha="left", va="baseline"):
    fig.text(x / w_px, 1 - y / h_px, s, fontsize=pt(size), color=color, fontweight=weight, ha=ha, va=va)


def style_value_axis(ax, axis="x"):
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(AXIS)
    ax.tick_params(axis="x", length=0, labelsize=pt(11.5), pad=6)
    ax.tick_params(axis="y", length=0, labelsize=pt(13), pad=10)
    ax.grid(axis=axis, color=GRID, linewidth=1.0, linestyle="-")
    ax.set_axisbelow(True)


def hbar(ax, y, w, h, color, px_per_x, px_per_y, zorder=3):
    """水平条：基线端方角、数据端 4px 圆角；太短画不下圆角时画方条（至少 1.5px 可见）。"""
    r = 4.0
    w_px = w * px_per_x
    if w_px < 2 * r + 1:
        ax.add_patch(Rectangle((0, y - h / 2), max(w, 1.5 / px_per_x), h, facecolor=color,
                               edgecolor="none", zorder=zorder))
        return
    rx, ry = r / px_per_x, r / px_per_y
    ax.add_patch(Rectangle((0, y - h / 2), w - rx, h, facecolor=color, edgecolor="none", zorder=zorder))
    ax.add_patch(FancyBboxPatch((w - 2 * rx, y - h / 2), 2 * rx, h,
                                boxstyle="round,pad=0,rounding_size=%r" % rx,
                                mutation_aspect=ry / rx, facecolor=color, edgecolor="none", zorder=zorder))


def legend_swatches(fig, w_px, h_px, x, y, items):
    """图例：色块 + 文字（文字用正文墨色，不用系列色）。返回结束的 x。"""
    for color, label in items:
        fig.patches.append(FancyBboxPatch(
            (x / w_px, 1 - (y + 11) / h_px), 12 / w_px, 12 / h_px, transform=fig.transFigure,
            boxstyle="round,pad=0,rounding_size=0.002", facecolor=color, edgecolor="none"))
        fig_text(fig, w_px, h_px, x + 18, y + 10.5, label, 12.5, INK_2)
        x += 18 + 12.5 * text_units(label) + 22
    return x


def text_units(s):
    """粗估文字宽度（以字号为单位）：中日文全宽 1，其它约 0.55。仅用于图例排布。"""
    return sum(1.0 if ord(c) > 0x2E80 else 0.56 for c in s)


def save(fig, out_dir, name):
    path = os.path.join(out_dir, name)
    fig.savefig(path, format="svg", metadata={"Date": None,
                                              "Creator": "bench/bin/charts.py (matplotlib %s)" % matplotlib.__version__})
    plt.close(fig)
    return path


def size_text(n):
    return "%dKB" % (n // 1024) if n % 1024 == 0 else "%dB" % n


def fmt_int1(v):
    return "{:,.1f}".format(v)


# ---------------------------------------------------------------------------
# 图 1：闭环 64 并发吞吐
# ---------------------------------------------------------------------------
def chart_throughput(reports, out_dir, root):
    rows = []
    for suffix, zh, en, role in ROWS:
        path, d = find_compare(reports, "closed64", suffix)
        rows.append(dict(suffix=suffix, label=T(zh, en), role=role, path=path, doc=d,
                         value=d["summary"]["official"]["throughput_rps"],
                         official_round=d["summary"]["official_round"],
                         conns=d["run"]["target"]["connections_per_node"]))
    base = rows[0]["value"]
    footer = compare_conditions([r["doc"] for r in rows])

    W, H = 960, 480
    fig = new_fig(W, H)
    fig_text(fig, W, H, 24, 34, T("闭环 64 并发：吞吐（req/s）", "Closed loop, 64 concurrent: throughput (req/s)"), 17, INK, "bold")
    fig_text(fig, W, H, 24, 58, T("1KB 回显、单提供者、直连；括号内倍数 = 相对本框架 fixed（缺省）。direct 让业务方法在 IO 线程上执行，非缺省，对外以 fixed 为准",
                                  "1KB echo, one provider, direct. Multiples are relative to this framework's default (fixed). direct runs business code on IO threads; not the default"), 12, INK_2)
    legend_swatches(fig, W, H, 24, 76, [(ACCENT, T("本框架缺省", "this framework, default")),
                                         (ACCENT_LIGHT, T("本框架 opt-in", "this framework, opt-in")),
                                         (PEER, T("对比对象", "peers"))])

    left, top, pw, ph = 262, 112, 600, 290
    ax = add_axes_px(fig, W, H, left, top, pw, ph)
    ys, y = [], 0.0
    for i in range(len(rows)):
        if i in GROUP_BREAKS:
            y += 0.55
        ys.append(y)
        y += 1.0
    ymax = ys[-1] + 0.6
    xmax = 160000
    ax.set_xlim(0, xmax)
    ax.set_ylim(ymax, -0.6)
    px_x, px_y = pw / xmax, ph / (ymax + 0.6)
    bar_h = 22 / px_y
    colors = {"ours": ACCENT, "ours-opt": ACCENT_LIGHT, "peer": PEER}
    for r, yy in zip(rows, ys):
        hbar(ax, yy, r["value"], bar_h, colors[r["role"]], px_x, px_y)
        mult = "" if r is rows[0] else "（%.2f×）" % (r["value"] / base) if ZH else " (%.2f×)" % (r["value"] / base)
        vt = ax.text(r["value"] + 8 / px_x, yy, fmt_int1(r["value"]), va="center", ha="left",
                     fontsize=pt(13), color=INK, fontweight="bold" if r["role"] == "ours" else "normal")
        if mult:
            bb = vt.get_window_extent(renderer=fig.canvas.get_renderer())
            x_end = ax.transData.inverted().transform((bb.x1, bb.y0))[0]
            ax.text(x_end + 2 / px_x, yy, mult, va="center", ha="left", fontsize=pt(12), color=MUTED)
    ax.set_yticks(ys)
    ax.set_yticklabels([r["label"] for r in rows])
    for tl, r in zip(ax.get_yticklabels(), rows):
        if r["role"] != "peer":
            tl.set_fontweight("bold")
    ax.xaxis.set_major_locator(FixedLocator([0, 40000, 80000, 120000, 160000]))
    ax.set_xticklabels(["0", "40K", "80K", "120K", "160K"])
    style_value_axis(ax, "x")
    fig_text(fig, W, H, 24, H - 32, footer, 10.5, MUTED)
    svg = save(fig, out_dir, "throughput-closed64.svg")

    lines = caption_header("throughput-closed64.svg", T("闭环 64 并发吞吐", "closed64 throughput"))
    lines += ["| 图中的条 | 吞吐 (req/s) | 相对 fixed | 正式轮 | 连接数 | 来源 JSON |", "|---|---|---|---|---|---|"]
    for r in rows:
        lines.append("| %s | %s | %s | 第 %d 轮 | %d | `%s` |" % (
            r["label"], fmt_int1(r["value"]), "—" if r is rows[0] else "%.2f×" % (r["value"] / base),
            r["official_round"], r["conns"], rel(r["path"], root)))
    lines += ["", "取数字段：`summary.official.throughput_rps`（正式轮 = 3 轮中窗口吞吐的中位那一轮，`summary.selection_rule`；"
              "统计窗口 [20s, 55s]）。图上数值保留 1 位小数，倍数 = 该值 / 本框架 fixed 的值，保留 2 位。",
              "连接数取 `run.target.connections_per_node`。", "", footer, ""]
    write_caption(out_dir, "throughput-closed64.md", lines)
    return svg, rows


# ---------------------------------------------------------------------------
# 图 2：开环 10k req/s 的 p50 / p99
# ---------------------------------------------------------------------------
def chart_latency(reports, out_dir, root):
    rows = []
    for suffix, zh, en, role in ROWS:
        path, d = find_compare(reports, "open10k", suffix)
        off = d["summary"]["official"]
        sp = d["summary"]["spread"]["p99_ms"]
        rows.append(dict(suffix=suffix, label=T(zh, en), role=role, path=path, doc=d,
                         p50=off["p50_ms"], p99=off["p99_ms"], p99_min=sp["min"], p99_max=sp["max"],
                         rounds=sp["rounds"], official_round=d["summary"]["official_round"],
                         rate=d["profile"]["parsed"]["load"]["rate"]))
    footer = compare_conditions([r["doc"] for r in rows])

    W, H = 960, 500
    fig = new_fig(W, H)
    fig_text(fig, W, H, 24, 34, T("开环 10,000 req/s：p50 与 p99 延迟（ms，对数轴）",
                                  "Open loop 10,000 req/s: p50 and p99 latency (ms, log scale)"), 17, INK, "bold")
    fig_text(fig, W, H, 24, 58, T("延迟从计划发出时刻算（修正 coordinated omission）；灰带 = 同一张卡三轮 p99 的极差，单连接的 gRPC / Dubbo 各轮波动大，只看量级",
                                  "Latency from the scheduled send time (corrects coordinated omission). Gray band = range of p99 across the 3 rounds"), 12, INK_2)
    # 图例（点）
    lx, ly = 24, 84
    for color, label, r in ((ACCENT_LIGHT, "p50", 5), (ACCENT_DARK, "p99", 5)):
        fig.add_artist(matplotlib.patches.Ellipse(((lx + 6) / W, 1 - (ly - 4) / H), 2 * r / W, 2 * r / H, transform=fig.transFigure,
                                                 facecolor=color, edgecolor=SURFACE, linewidth=1.5))
        fig_text(fig, W, H, lx + 18, ly + 1, label, 12.5, INK_2)
        lx += 18 + 12.5 * text_units(label) + 22
    fig.add_artist(Rectangle((lx / W, 1 - (ly + 2) / H), 26 / W, 12 / H, transform=fig.transFigure,
                             facecolor=BAND, edgecolor="none"))
    fig_text(fig, W, H, lx + 32, ly + 1, T("三轮 p99 极差", "p99 range across 3 rounds"), 12.5, INK_2)

    left, top, pw, ph = 262, 112, 640, 300
    ax = add_axes_px(fig, W, H, left, top, pw, ph)
    ys, y = [], 0.0
    for i in range(len(rows)):
        if i in GROUP_BREAKS:
            y += 0.55
        ys.append(y)
        y += 1.0
    ymax = ys[-1] + 0.6
    ax.set_xscale("log")
    xlo, xhi = 0.15, 100.0
    ax.set_xlim(xlo, xhi)
    ax.set_ylim(ymax, -0.6)
    ticks = [0.2, 0.5, 1, 2, 5, 10, 20, 50, 100]
    ax.xaxis.set_major_locator(FixedLocator(ticks))
    ax.xaxis.set_minor_locator(NullLocator())
    ax.set_xticklabels(["0.2", "0.5", "1", "2", "5", "10", "20", "50", "100 ms"])
    style_value_axis(ax, "x")
    import math
    px_per_dec = pw / (math.log10(xhi) - math.log10(xlo))

    def shift(x, px):  # 在对数轴上按像素平移
        return x * 10 ** (px / px_per_dec)

    px_y = ph / (ymax + 0.6)
    for r, yy in zip(rows, ys):
        # 三轮 p99 极差：浅灰带
        ax.add_patch(Rectangle((r["p99_min"], yy - 7 / px_y), r["p99_max"] - r["p99_min"], 14 / px_y,
                               facecolor=BAND, edgecolor="none", zorder=1))
        ax.plot([r["p50"], r["p99"]], [yy, yy], color=AXIS if r["role"] == "peer" else ACCENT_LIGHT,
                linewidth=2, solid_capstyle="round", zorder=2)
        ax.scatter([r["p50"]], [yy], s=(10 * 72 / PX) ** 2, color=ACCENT_LIGHT, edgecolors=SURFACE, linewidths=1.5, zorder=4)
        ax.scatter([r["p99"]], [yy], s=(11 * 72 / PX) ** 2, color=ACCENT_DARK, edgecolors=SURFACE, linewidths=1.5, zorder=5)
        ax.text(shift(r["p50"], -10), yy, "%.3f" % r["p50"], ha="right", va="center", fontsize=pt(12), color=INK_2, zorder=6)
        right = max(r["p99"], r["p99_max"])
        ax.text(shift(right, 10), yy, "%.3f" % r["p99"], ha="left", va="center", fontsize=pt(13), color=INK,
                fontweight="bold", zorder=6)
    ax.set_yticks(ys)
    ax.set_yticklabels([r["label"] for r in rows])
    for tl, r in zip(ax.get_yticklabels(), rows):
        if r["role"] != "peer":
            tl.set_fontweight("bold")
    fig_text(fig, W, H, 24, H - 32, footer, 10.5, MUTED)
    svg = save(fig, out_dir, "tail-latency-open10k.svg")

    lines = caption_header("tail-latency-open10k.svg", T("开环 10k p50 / p99", "open10k p50 / p99"))
    lines += ["| 图中的行 | p50 (ms) | p99 (ms) | 三轮 p99 极差 (ms) | 正式轮 | 来源 JSON |", "|---|---|---|---|---|---|"]
    for r in rows:
        lines.append("| %s | %.3f | %.3f | %.3f ~ %.3f | 第 %d 轮 | `%s` |" % (
            r["label"], r["p50"], r["p99"], r["p99_min"], r["p99_max"], r["official_round"], rel(r["path"], root)))
    lines += ["", "取数字段：`summary.official.p50_ms` / `p99_ms`（正式轮 = 3 轮中窗口 p99 的中位那一轮，`summary.selection_rule`；"
              "统计窗口 [20s, 55s]，开环 %d req/s，延迟从计划发出时刻算）；极差取 `summary.spread.p99_ms.min` / `max`。" % rows[0]["rate"],
              "横轴为对数轴（p99 跨 0.5 ~ 22ms，三轮极差到 72ms），数值标签保留 3 位小数，与 JSON 一致。", "", footer, ""]
    write_caption(out_dir, "tail-latency-open10k.md", lines)
    return svg, rows


# ---------------------------------------------------------------------------
# 图 3：故障注入下离群摘除开 / 关（探针口径）
# ---------------------------------------------------------------------------
FAULT_GROUPS = [
    # (目录, 运行名前缀, 卡片 id, 期望 fault kind, 中文标题, 英文标题)
    ("kill", "failfast-kill9", "fault-kill-node-failfast-v1", "kill-9",
     "kill -9 一个提供者（failfast）", "kill -9 one provider (failfast)"),
    ("timeout", "timeout", "fault-kill-node-failover-v1", "toxic-timeout",
     "连接静默挂起（toxic-timeout，failover）", "Connection silently hangs (toxic-timeout, failover)"),
]
RUN_ORDER = [("on", 1), ("on", 2), ("off", 1), ("off", 2)]


def load_fault_runs(reports):
    base = os.path.join(reports, PROBE_BATCH)
    groups = []
    for sub, prefix, card, kind, zh, en in FAULT_GROUPS:
        runs = []
        for od, n in RUN_ORDER:
            pattern = os.path.join(base, sub, "%s-%s-%d" % (prefix, od, n), card + "-2*.json")
            hits = sorted(glob.glob(pattern))
            if len(hits) != 1:
                raise DataError("期望 1 个结果文件，找到 %d 个：%s" % (len(hits), pattern))
            d = load(hits[0])
            env = d["environment"]
            rnd = d["rounds"][0]
            faults = rnd["faults"]
            problems = []
            if d["profile"]["id"] != card:
                problems.append("profile.id = %s" % d["profile"]["id"])
            if d["run"]["target"]["outlier_detection"]["enabled"] != od:
                problems.append("outlier_detection.enabled = %s，目录名是 %s" % (d["run"]["target"]["outlier_detection"]["enabled"], od))
            if not env["git_sha"].startswith(PROBE_SHA) or not env["git_dirty"]:
                problems.append("git_sha / git_dirty = %s / %s（期望 %s + 未提交改动）" % (env["git_sha"][:7], env["git_dirty"], PROBE_SHA))
            if len(d["rounds"]) != 1 or len(faults) != 1 or faults[0]["kind"] != kind or faults[0]["status"] != "ok":
                problems.append("轮数 / 故障不符")
            if not d["validity"]["passed"]:
                problems.append("validity.passed = false")
            if problems:
                raise DataError("%s：%s" % (hits[0], "；".join(problems)))
            f = faults[0]
            rec = f["recovery"]
            runs.append(dict(od=od, n=n, path=hits[0], doc=d,
                             errors=d["summary"]["official"]["window_errors"],
                             errors_by_type=rnd["errors_by_type"],
                             recovered=rec["recovered"], recovery_s=rec["errors_recovery_s"],
                             fault_at_ms=f["executed_at_ms"], window_end_ms=rnd["window"]["end_ms"],
                             window_start_ms=rnd["window"]["start_ms"],
                             ejections=f.get("outlier_ejections"), restores=f.get("outlier_restores")))
        groups.append(dict(sub=sub, card=card, kind=kind, title=T(zh, en), runs=runs))
    return groups


def chart_fault(reports, out_dir, root):
    groups = load_fault_runs(reports)
    docs = [r["doc"] for g in groups for r in g["runs"]]
    d0 = docs[0]
    env, senv = d0["environment"], d0["server_environment"]
    loadp = d0["profile"]["parsed"]["load"]
    for d in docs:
        lp = d["profile"]["parsed"]["load"]
        if (lp["rate"], lp["duration"], lp["timeout"], d["environment"]["cpus_allowed"]) != \
                (loadp["rate"], loadp["duration"], loadp["timeout"], env["cpus_allowed"]):
            raise DataError("故障运行的负载口径不一致")
    providers = d0["profile"]["parsed"]["target"]["providers"]
    size = d0["profile"]["parsed"]["target"]["payload"]["size"]
    win = (d0["rounds"][0]["window"]["start_ms"] / 1000, d0["rounds"][0]["window"]["end_ms"] / 1000)
    cpu_short = env["cpu_model"].replace(" with Radeon Graphics", "")
    footer = T(
        "测试条件：同机 WSL2 loopback · %s · 服务端 cpu %s / 压测端 cpu %s（taskset 各 5 核）· %s 回显 · 开环 %d req/s · %d 个提供者经 ZooKeeper · 超时 %s\n"
        "单轮 %s（窗口 %g–%gs，t = 20s 注入故障）· 开 / 关各 2 次 · %s + 未提交改动（git_dirty）· 同机数字不可外推"
        % (cpu_short, senv["cpus_allowed"], env["cpus_allowed"], size_text(size), loadp["rate"], providers, loadp["timeout"],
           loadp["duration"], win[0], win[1], PROBE_SHA),
        "Same-host WSL2 loopback · %s · server cpu %s / load cpu %s · %s echo · open loop %d req/s · %d providers via ZooKeeper · timeout %s\n"
        "one %s round (window %g-%gs, fault at t = 20s) · 2 runs per setting · %s + uncommitted (git_dirty) · same-host numbers do not extrapolate"
        % (cpu_short, senv["cpus_allowed"], env["cpus_allowed"], size_text(size), loadp["rate"], providers, loadp["timeout"],
           loadp["duration"], win[0], win[1], PROBE_SHA))

    W, H = 960, 580
    fig = new_fig(W, H)
    fig_text(fig, W, H, 24, 34, T("故障注入：离群摘除 开 / 关 的窗口错误数与恢复时间", "Fault injection: outlier detection on / off — errors and recovery time"), 17, INK, "bold")
    legend_swatches(fig, W, H, 24, 76, [(ACCENT, T("离群摘除 开（缺省）", "outlier detection on (default)")),
                                         (PEER, T("离群摘除 关", "outlier detection off"))])

    col_left = [210, 640]
    col_w = [330, 270]
    row_top = [150, 330]
    row_h = 130
    err_max = max(r["errors"] for g in groups for r in g["runs"])
    err_xmax = 70000 if err_max <= 70000 else (int(err_max / 10000) + 1) * 10000
    cens = [(r["window_end_ms"] - r["fault_at_ms"]) / 1000 for g in groups for r in g["runs"] if not r["recovered"]]
    rec_vals = [r["recovery_s"] for g in groups for r in g["runs"] if r["recovered"]]
    rec_xmax = 45.0 if max(rec_vals + cens) <= 45 else 60.0

    fig_text(fig, W, H, col_left[0], 126, T("窗口错误数（60s 轮内，个）", "Errors in window (count)"), 13, INK, "bold")
    fig_text(fig, W, H, col_left[1], 126, T("错误恢复时间（s）", "Error recovery time (s)"), 13, INK, "bold")

    run_labels = [T("开 #1", "on #1"), T("开 #2", "on #2"), T("关 #1", "off #1"), T("关 #2", "off #2")]
    ys = [0, 1, 2.35, 3.35]
    for gi, g in enumerate(groups):
        fig_text(fig, W, H, 24, row_top[gi] - 2, g["title"], 13, INK, "bold")
        for ci in range(2):
            ax = add_axes_px(fig, W, H, col_left[ci], row_top[gi] + 6, col_w[ci], row_h)
            xmax = err_xmax if ci == 0 else rec_xmax
            ax.set_xlim(0, xmax)
            ax.set_ylim(ys[-1] + 0.6, -0.6)
            px_x, px_y = col_w[ci] / xmax, row_h / (ys[-1] + 1.2)
            bh = 18 / px_y
            for r, yy, lab in zip(g["runs"], ys, run_labels):
                color = ACCENT if r["od"] == "on" else PEER
                if ci == 0:
                    v = r["errors"]
                    hbar(ax, yy, v, bh, color, px_x, px_y)
                    ax.text(v + 6 / px_x, yy, "{:,}".format(v), va="center", ha="left", fontsize=pt(12.5),
                            color=INK, fontweight="bold" if r["od"] == "on" else "normal")
                elif r["recovered"]:
                    v = r["recovery_s"]
                    hbar(ax, yy, v, bh, color, px_x, px_y)
                    ax.text(v + 6 / px_x, yy, "%s s" % v, va="center", ha="left", fontsize=pt(12.5),
                            color=INK, fontweight="bold" if r["od"] == "on" else "normal")
                else:
                    v = (r["window_end_ms"] - r["fault_at_ms"]) / 1000
                    hbar(ax, yy, v, bh, PEER_LIGHT, px_x, px_y)
                    ax.text(6 / px_x, yy, T("未恢复：到轮末（%.0fs 后）仍在出错" % v, "not recovered by end of round (+%.0fs)" % v),
                            va="center", ha="left", fontsize=pt(12), color=INK, zorder=6)
            ax.set_yticks(ys)
            ax.set_yticklabels(run_labels if ci == 0 else [])
            if ci == 0:
                ax.xaxis.set_major_locator(FixedLocator([0, 20000, 40000, 60000]))
                ax.set_xticklabels(["0", "20K", "40K", "60K"])
            else:
                ax.xaxis.set_major_locator(FixedLocator([0, 10, 20, 30, 40]))
            style_value_axis(ax, "x")
            ax.tick_params(axis="y", labelsize=pt(12), labelcolor=INK_2)
    fig_text(fig, W, H, 24, H - 32, footer, 10.5, MUTED)
    svg = save(fig, out_dir, "fault-recovery.svg")

    lines = caption_header("fault-recovery.svg", T("离群摘除开 / 关的故障注入对照", "outlier detection on/off"))
    lines += ["**探针口径（git_dirty = true，%s + 未提交改动），不作数**，数字只说明机制；完整实验与判定见 [%s](../%s)。" % (PROBE_SHA, PROBE_DOC.split("/", 1)[1], PROBE_DOC.split("/", 1)[1]), "",
              "| 故障 | 运行 | 离群摘除 | 窗口错误数 | 错误类型 | 错误恢复 (s) | 摘除 / 放回 | 来源 JSON |", "|---|---|---|---|---|---|---|---|"]
    for g in groups:
        for r in g["runs"]:
            rec = "%s" % r["recovery_s"] if r["recovered"] else "未恢复（recovered = false，故障后 %.1fs 到轮末）" % ((r["window_end_ms"] - r["fault_at_ms"]) / 1000)
            lines.append("| %s | %s-%d | %s | %s | %s | %s | %s / %s | `%s` |" % (
                g["title"], r["od"], r["n"], r["od"], "{:,}".format(r["errors"]),
                ", ".join("%s %s" % (k, "{:,}".format(v)) for k, v in r["errors_by_type"].items()) or "—",
                rec, r["ejections"], r["restores"], rel(r["path"], root)))
    lines += ["", "取数字段：窗口错误数 `summary.official.window_errors`（单轮，统计窗口 [%gs, %gs]）；错误类型 `rounds[0].errors_by_type`；"
              "恢复时间 `rounds[0].faults[0].recovery.errors_recovery_s`（判据见同一对象的 `criterion`：故障后第一个"
              "\"之后连续 3 秒错误为 0\"的秒，只看第一次恢复）；`recovered = false` 时画到轮末的浅灰条并标注\"未恢复\"，条长 = "
              "`rounds[0].window.end_ms − faults[0].executed_at_ms`；摘除 / 放回 = `faults[0].outlier_ejections` / `outlier_restores`。" % win,
              "错误数与恢复时间是两种量纲，分成两列各用自己的线性轴（不用双轴）；同一列两行共用刻度。", "", footer, ""]
    write_caption(out_dir, "fault-recovery.md", lines)
    return svg, groups


def caption_header(svg_name, title):
    return ["# %s：数据与口径" % svg_name, "",
            "由 `python bench/bin/charts.py --reports bench/reports --out docs/images` 从归档 JSON 生成，请勿手改；"
            "数字以下表与来源 JSON 为准。", "", "图：%s。" % title, ""]


def write_caption(out_dir, name, lines):
    with open(os.path.join(out_dir, name), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


# ---------------------------------------------------------------------------
# 架构图（不读数据）
# ---------------------------------------------------------------------------
def chart_architecture(out_dir):
    W, H = 960, 828
    fig = new_fig(W, H)
    ax = fig.add_axes([0, 0, 1, 1])
    ax.set_xlim(0, W)
    ax.set_ylim(H, 0)
    ax.axis("off")

    def box(x, y, w, h, fill="#ffffff", edge=AXIS, lw=1.0, r=8, z=2):
        ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0,rounding_size=%d" % r,
                                    facecolor=fill, edgecolor=edge, linewidth=lw, zorder=z))

    def text(x, y, s, size, color=INK, weight="normal", ha="left", va="baseline", z=5):
        ax.text(x, y, s, fontsize=pt(size), color=color, fontweight=weight, ha=ha, va=va, zorder=z)

    def arrow(points, color=ACCENT, lw=1.6, z=3, both=False):
        xs = [p[0] for p in points]
        ys = [p[1] for p in points]
        if len(points) > 2:
            ax.plot(xs[:-1], ys[:-1], color=color, linewidth=lw, solid_joinstyle="miter", zorder=z)
        style = "<|-|>" if both else "-|>"
        ax.add_patch(FancyArrowPatch(points[-2], points[-1], arrowstyle=style + ",head_length=7,head_width=4",
                                     color=color, linewidth=lw, shrinkA=0, shrinkB=0, zorder=z,
                                     mutation_scale=1))

    def stage(x, y, w, title, desc, h=46):
        box(x, y, w, h, fill="#ffffff", edge=AXIS, r=6)
        ax.add_patch(Rectangle((x, y + 8), 3, h - 16, facecolor=ACCENT, edgecolor="none", zorder=4))
        text(x + 16, y + 20, title, 13.5, INK, "bold")
        text(x + 16, y + 38, desc, 12, INK_2)

    MX, MW = 40, 590         # 主列容器
    SX, SW = MX + 26, MW - 52  # 阶段框
    # 调用方
    box(MX, 18, MW, 50, fill=PANEL, edge=AXIS, r=8)
    text(MX + 16, 39, T("调用方：你的应用", "Caller: your application"), 13.5, INK, "bold")
    text(MX + 16, 58, T("@RpcReference 字段 / client.reference(Itf.class) → 动态代理（同步方法 = 在 future 上等待）",
                        "@RpcReference field / client.reference(Itf.class) → dynamic proxy (sync = wait on future)"), 12, INK_2)
    # 消费端容器
    cy0 = 96
    stages_c = [
        (T("Filter 链", "Filter chain"), T("内置 Deadline + 你的 ClientFilter，按 order() 稳定排序", "built-in Deadline + your ClientFilters, ordered")),
        (T("Cluster 容错", "Cluster"), T("failfast / failover / failback；只重试传输失败、只在 retries > 0 时", "failfast / failover / failback; retries transport failures only")),
        (T("负载均衡 · 离群摘除", "Load balancing · outlier detection"), T("roundrobin / p2c；按节点摘除超时 / 传输失败 / 过载，到期放回", "roundrobin / p2c; eject on timeouts / transport failures / overload")),
        (T("连接池", "Connection pool"), T("每节点 N 条（缺省 2）· 在途表 · 建连时协商序列化", "N connections per node (default 2) · in-flight table · serializer negotiation")),
    ]
    stage_h, gap, head = 46, 18, 34
    ch = head + len(stages_c) * stage_h + (len(stages_c) - 1) * gap + 16
    box(MX, cy0, MW, ch, fill=PANEL, edge=AXIS, r=10, z=1)
    text(MX + 16, cy0 + 23, T("消费端 RpcClient", "Consumer · RpcClient"), 14, INK, "bold")
    ys_c = []
    for i, (t, d) in enumerate(stages_c):
        y = cy0 + head + i * (stage_h + gap)
        ys_c.append(y)
        stage(SX, y, SW, t, d, stage_h)
        if i:
            arrow([(MX + MW / 2, y - gap), (MX + MW / 2, y)])
    arrow([(MX + MW / 2, 68), (MX + MW / 2, cy0 + head)])
    # 线上帧
    wy = cy0 + ch + 28
    wire_h = 44
    box(MX + 26, wy, MW - 52, wire_h, fill="#ffffff", edge=ACCENT, lw=1.4, r=22)
    text(MX + MW / 2, wy + 19, T("TCP · 协议 v2 帧", "TCP · protocol v2 frame"), 13.5, INK, "bold", ha="center")
    text(MX + MW / 2, wy + 36, T("23 字节定长头 | attachments | 信封（参数逐个序列化）；响应沿原连接按 requestId 返回",
                                 "23-byte header | attachments | envelope (args serialized one by one); reply by requestId"), 12, INK_2, ha="center")
    arrow([(MX + MW / 2, cy0 + ch), (MX + MW / 2, wy)], both=True)
    # 服务端容器
    sy0 = wy + wire_h + 28
    arrow([(MX + MW / 2, wy + wire_h), (MX + MW / 2, sy0 + head)], both=True)
    stages_s = [
        (T("IO 线程", "IO threads"), T("解帧、解 attachments；停机 / 过载闸门（不碰 body，被拒请求不反序列化）", "decode frame + attachments; shutdown / overload gate (body untouched)")),
        (T("业务执行器", "Business executor"), T("固定池 + 有界队列（缺省）/ 虚拟线程 / direct（opt-in）；满载回 SERVER_OVERLOADED", "fixed pool + bounded queue (default) / virtual / direct (opt-in)")),
        (T("Filter 链", "Filter chain"), T("内置 Deadline 检查（排队中已过期不执行）+ 你的 ServerFilter", "built-in deadline check + your ServerFilters")),
        (T("服务实现", "Service implementation"), T("方法表（method + paramSig）→ 按声明类型惰性反序列化参数 → 调用；future 完成时回包", "method table → lazy arg decoding by declared type → invoke; futures not joined")),
    ]
    sh = head + len(stages_s) * stage_h + (len(stages_s) - 1) * gap + 16
    box(MX, sy0, MW, sh, fill=PANEL, edge=AXIS, r=10, z=1)
    text(MX + 16, sy0 + 23, T("服务端 RpcServer", "Provider · RpcServer"), 14, INK, "bold")
    ys_s = []
    for i, (t, d) in enumerate(stages_s):
        y = sy0 + head + i * (stage_h + gap)
        ys_s.append(y)
        stage(SX, y, SW, t, d, stage_h)
        if i:
            arrow([(MX + MW / 2, y - gap), (MX + MW / 2, y)])

    # 旁路：Micrometer 指标、注册中心
    RX, RW = 690, 240
    side = MUTED
    my = ys_c[0] - 8
    box(RX, my, RW, 104, fill="#ffffff", edge=AXIS, r=8)
    text(RX + 14, my + 22, T("Micrometer 指标", "Micrometer metrics"), 13.5, INK, "bold")
    text(RX + 14, my + 40, "rpc-metrics-micrometer", 11.5, INK_2)
    text(RX + 14, my + 60, T("调用 Timer（service / method / status）", "call Timer (service/method/status)"), 11.5, INK_2)
    text(RX + 14, my + 78, T("在途 · 连接数 Gauge", "in-flight · connection Gauges"), 11.5, INK_2)
    text(RX + 14, my + 96, T("可内嵌 Prometheus /metrics", "embeddable Prometheus /metrics"), 11.5, INK_2)
    fy = ys_c[0] + stage_h / 2
    arrow([(SX + SW, fy), (RX, fy)], color=side, lw=1.2)
    text((SX + SW + RX) / 2, fy - 6, "ClientFilter", 11, MUTED, ha="center")
    sfy = ys_s[2] + stage_h / 2
    rx_far = RX + RW + 14
    arrow([(SX + SW, sfy), (rx_far, sfy), (rx_far, my + 70), (RX + RW, my + 70)], color=side, lw=1.2)
    text(rx_far - 8, sfy - 6, "ServerFilter", 11, MUTED, ha="right")

    ry = ys_c[2] - 6
    rh = 110
    box(RX, ry, RW, rh, fill="#ffffff", edge=AXIS, r=8)
    text(RX + 14, ry + 22, T("注册中心", "Registry"), 13.5, INK, "bold")
    text(RX + 14, ry + 41, "ZooKeeper / Nacos", 12, INK_2)
    text(RX + 14, ry + 61, "/rpc/{interface:version:group}", 11, INK_2)
    text(RX + 14, ry + 78, "  /providers/{host:port}", 11, INK_2)
    text(RX + 14, ry + 98, T("同一套契约测试（TCK）", "shared contract tests (TCK)"), 11.5, INK_2)
    py = ys_c[2] + stage_h / 2
    arrow([(RX, py), (SX + SW, py)], color=side, lw=1.2)
    text((SX + SW + RX) / 2, py - 6, T("订阅推送", "subscribe/push"), 11, MUTED, ha="center")
    regx = RX + 70
    iy = ys_s[0] + stage_h / 2
    arrow([(MX + MW, iy), (regx, iy), (regx, ry + rh)], color=side, lw=1.2)
    text(regx + 8, iy - 30, T("register / unregister", "register / unregister"), 11, MUTED)
    text(regx + 8, iy - 14, T("（bind 后，后台重试）", "(after bind, retried)"), 11, MUTED)

    text(MX, H - 18, T("四个 SPI（META-INF/rpc/<接口全名>）：RegistryFactory · Serializer · LoadBalance · Cluster；实例由每个 RpcClient / RpcServer 各自创建、各自关闭",
                       "Four SPIs (META-INF/rpc/<interface>): RegistryFactory · Serializer · LoadBalance · Cluster; instances owned by each client / server"), 11.5, INK_2)
    return save(fig, out_dir, "architecture.svg")


# ---------------------------------------------------------------------------
def main(argv=None):
    p = argparse.ArgumentParser(description="从归档 JSON 生成 README 图（SVG）")
    p.add_argument("--reports", default="bench/reports", help="归档根目录（含 %s/ 与 probes/）" % COMPARE_BATCH)
    p.add_argument("--out", default="docs/images", help="输出目录")
    a = p.parse_args(argv)
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    os.makedirs(a.out, exist_ok=True)
    font = setup_fonts()
    try:
        outs = [chart_throughput(a.reports, a.out, root)[0],
                chart_latency(a.reports, a.out, root)[0],
                chart_fault(a.reports, a.out, root)[0],
                chart_architecture(a.out)]
    except (DataError, KeyError, OSError) as e:
        print("charts.py: %s: %s" % (type(e).__name__, e), file=sys.stderr)
        return 1
    print("font: %s" % (font or "DejaVu Sans (英文标签)"))
    for o in outs:
        print("wrote %s (%d bytes)" % (o.replace(os.sep, "/"), os.path.getsize(o)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
