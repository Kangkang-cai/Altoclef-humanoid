#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
随行 / 角色 验证脚本 —— **不需要对端**，直接打各实例的 localhost bridge（HTTP）并读游戏内日志。

为什么要有它：多 bot 随行这一批改动（role 降档 / @follow work / 贴 3 格跟随 / 偏离上限）
的判据散在"几何距离 + 日志标记"里，靠人盯屏幕既不可复现也不可信。这里把它们变成可重复跑的断言。

与 bridge_smoke_test.py 的关系：**复用它的 Bot（HTTP / 限流 / 事件游标），不改它**。
分工：smoke_test 管"命令回执契约"；本脚本管"多 bot 的行为与几何"。

用法（Windows；脚本只用标准库，任何 Python 3.8+ 都行）：
    推荐直接用托管解释器的绝对路径 —— 它就在 PATH 首位，且与 bridge_smoke_test.py 的文档一致：
        C:\\Users\\Kangkang\\.workbuddy\\binaries\\python\\versions\\3.13.12\\python.exe

    python altoclef/bridge_follow_test.py ^
        --main bot_1=http://127.0.0.1:8761 ^
        --sub  bot_2=http://127.0.0.1:8762 ^
        --leader <主bot的MC玩家名，如 qiaoqiao> ^
        --gamedir-sub "D:/minecraft/.minecraft/versions/1.18.2-Fabric-2" ^
        --gamedir-main "D:/minecraft/.minecraft/versions/1.18.2-Fabric-1"

    # 只看用例
    python altoclef/bridge_follow_test.py --list

    # 只跑不需要两个 bot 的用例（role / 无效目标）
    python altoclef/bridge_follow_test.py --main bot_1=http://127.0.0.1:8761

⚠️ 别用 PATH 里的 WindowsApps\\python（那通常是微软商店的占位程序，会弹商店而不是执行脚本）。
   脚本自带 sys.path 修正，所以从哪个目录跑都行。

设计要点
--------
1. **几何断言**：`/status.pos` 是双 bot 都能读到的硬数据。"贴 3 格跟随"因此可以客观断言 ——
   先等距离收敛，再在一个稳定窗口里采样，判 min/median/max。不再依赖"看起来贴着"。
2. **日志断言用锚点**：开始前记录每个日志文件的字节偏移，只读**锚点之后**的新增内容。
   否则会拿上一局的历史日志当"通过"（假阳性），这是这类脚本最容易翻的车。
3. **契约型断言用"若出现则必须满足"**：例如囤木目标，只在日志里真出现 `Stockpiling wood`
   时才断言它是 `/8` 而不是 `/25` —— 避免"这局还没走到该阶段"被误判成失败。
"""

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from bridge_smoke_test import Bot  # 复用 HTTP/限流/事件游标，不重复造
except Exception as ex:  # pragma: no cover
    print("无法导入 bridge_smoke_test（应与本脚本同目录）：%s" % ex)
    sys.exit(2)

# --- 随行语义常量，必须与 FollowAndWorkTask.java 一致 ---
FOLLOW_DISTANCE = 3.0
STRAY_LIMIT = 32.0
WORK_MAX_SEC = 30

# 贴 3 格 + 0.5 滞回 + 玩家碰撞箱，稳态水平距离的正常区间
STEADY_MIN = 1.0
STEADY_MAX = 6.0


# ============================ 日志读取（带锚点） ============================

class GameLogs:
    """定位 <gamedir>/logs/altoclef/<最新会话>/，支持"只看开始之后的新增"。"""

    def __init__(self, gamedir):
        self.gamedir = Path(gamedir) if gamedir else None
        self._session = None
        self._anchor = {}

    def session(self):
        if self._session is not None:
            return self._session
        if not self.gamedir:
            return None
        base = self.gamedir / "logs" / "altoclef"
        if not base.is_dir():
            return None
        cands = sorted([d for d in base.iterdir() if d.is_dir() and d.name[:4].isdigit()])
        self._session = cands[-1] if cands else None
        return self._session

    def path(self, name):
        s = self.session()
        return (s / name) if s else None

    def anchor(self):
        """记录当前各日志末尾偏移。之后再读只拿哨点之后的新增。"""
        self._anchor = {}
        for n in ("diag.log", "debugstate.log", "tasks.log"):
            p = self.path(n)
            try:
                self._anchor[n] = p.stat().st_size if p and p.exists() else 0
            except OSError:
                self._anchor[n] = 0
        return self._anchor

    def new_text(self, name):
        p = self.path(name)
        if not p or not p.exists():
            return ""
        start = self._anchor.get(name, 0)
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                f.seek(start)
                return f.read()
        except OSError:
            return ""

    def tail_text(self, name, max_bytes=300_000):
        """读文件【尾部】。用途：断言"本会话内出现过某个只可能由我们产生的标记" ——
        这类标记可能在脚本启动之前就已经写了（例：次 bot 引导在客户端一上线就跑），
        用锚点会漏，用尾部不会。⚠️ 只对【不会被别的功能写入】的独有标记使用，否则会假阳性。"""
        p = self.path(name)
        if not p or not p.exists():
            return ""
        try:
            size = p.stat().st_size
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                if size > max_bytes:
                    f.seek(size - max_bytes)
                    f.readline()  # 丢掉可能被截断的半行
                return f.read()
        except OSError:
            return ""

    def tail_grep(self, name, pattern, max_bytes=300_000):
        rx = re.compile(pattern)
        return [ln for ln in self.tail_text(name, max_bytes).splitlines() if rx.search(ln)]

    def grep(self, name, pattern):
        rx = re.compile(pattern)
        return [ln for ln in self.new_text(name).splitlines() if rx.search(ln)]


# ============================ 几何采样 ============================

def xz_distance(a, b):
    pa, pb = a.get("pos") or {}, b.get("pos") or {}
    try:
        return ((float(pa["x"]) - float(pb["x"])) ** 2 + (float(pa["z"]) - float(pb["z"])) ** 2) ** 0.5
    except Exception:
        return None


def same_dim(a, b):
    return ((a.get("pos") or {}).get("dim") or "") == ((b.get("pos") or {}).get("dim") or "")


def summarize(samples):
    xs = sorted(s for s in samples if s is not None)
    if not xs:
        return None
    return {"n": len(xs), "min": xs[0], "median": xs[len(xs) // 2], "max": xs[-1]}


# ============================ 用例 ============================

def case_role(main, sub, results):
    """C1: role 字段必须存在且取值正确（对端据此区分主次）。"""
    print("\n---- C1) /status.role 字段 ----")
    for label, b, expect in (("主", main, "main"), ("次", sub, "sub")):
        if b is None:
            print("  [SKIP] %s bot 未提供" % label)
            continue
        try:
            s = b.status()
        except Exception as ex:
            print("  [%s] ✗ 读 /status 失败：%s" % (b.name, ex))
            results.append(("role(%s)" % b.name, False))
            continue
        got = s.get("role")
        ok = isinstance(got, str) and got.strip() != ""
        print("  [%s] role=%r" % (b.name, got))
        if ok and expect and got.strip().lower() != expect:
            print("      · 提示：期望 %r，实际 %r —— 检查该实例 bridge.json 的 role 字段"
                  "（旧配置没有该键 ⇒ 一律按 main）" % (expect, got))
        results.append(("role(%s) 存在" % b.name, ok))
        if expect:
            results.append(("role(%s)==%s" % (b.name, expect), (got or "").strip().lower() == expect))


def case_follow_geometry(main, sub, leader, results, approach_timeout, steady_sec):
    """C2/C3: 贴 3 格跟随（几何）+ 偏离不超 32 格。"""
    print("\n---- C2/C3) 贴 %.0f 格跟随 + 偏离上限 %.0f 格 ----" % (FOLLOW_DISTANCE, STRAY_LIMIT))
    if main is None or sub is None:
        print("  [SKIP] 需要同时提供 --main 与 --sub")
        results.append(("跟随几何", None))
        return
    if not leader:
        print("  [SKIP] 未提供 --leader（主 bot 的 MC 玩家名）")
        results.append(("跟随几何", None))
        return

    # 让主 bot 站住，否则主 bot 自己在跑主线，距离没有稳态可言
    try:
        main.post_paced("@idle")
        print("  [%s] 已下发 @idle 让主 bot 站住" % main.name)
    except Exception as ex:
        print("  [%s] @idle 失败：%s" % (main.name, ex))
    time.sleep(2)

    # ---- 阶段零：会合（必须！） ----
    # ⚠️ (2026-10-06 20:0x 实测教训) 客户端只下发约 48 格内的玩家实体
    # (entity-tracking-range.players)，所以**"跟随"在远距离根本不成立** —— 长距离只能靠
    # 【共享坐标】会合，这正是 /status.pos 的用途。不先会合就测跟随，测的其实是"会合"，
    # 必然失败：次 bot 的 last-known 位置就在自己脚下附近 ⇒ FollowPlayerTask 判定
    # "走到了但目标不在" ⇒ 直接放弃（log 会打 [NAV-STUCK] frozen）。
    try:
        mp = main.status().get("pos") or {}
        goto = "@goto %d %d %d %s" % (int(mp["x"]), int(mp["y"]), int(mp["z"]),
                                      mp.get("dim") or "overworld")
    except Exception as ex:
        goto = None
        print("  [%s] 读主 bot 坐标失败：%s" % (sub.name, ex))
    if goto:
        print("  [%s] 阶段零·会合 → %s" % (sub.name, goto))
        try:
            code, _ = sub.post_paced(goto)
            print("      HTTP %s；等距离 ≤ 8（最多 %ds）" % (code, approach_timeout))
        except Exception as ex:
            print("      ✗ 下发失败：%s" % ex)
        met = False
        end = time.time() + approach_timeout
        while time.time() < end:
            try:
                d = xz_distance(main.status(), sub.status())
                if d is not None and d <= 8:
                    print("      ✓ 已会合（距离 %.1f）" % d)
                    met = True
                    break
            except Exception:
                pass
            time.sleep(2)
        if not met:
            print("      ✗ 会合失败（两 bot 相距过远 / 地形不通 / 不同维度）")
        results.append(("会合: @goto 到主bot坐标", met))
        if not met:
            return
    time.sleep(2)

    # 下发随行。注意 leader 是【MC 玩家名】，不是 bridge 的 botId。
    try:
        code, resp = sub.post_paced("@follow %s work" % leader)
        print("  [%s] @follow %s work → HTTP %s" % (sub.name, leader, code))
    except Exception as ex:
        print("  [%s] ✗ 下发失败：%s" % (sub.name, ex))
        results.append(("跟随几何", False))
        return

    # 阶段一：等收敛
    print("  · 阶段一：等距离收敛（最多 %ds）" % approach_timeout)
    end = time.time() + approach_timeout
    got_close = False
    best = None
    while time.time() < end:
        try:
            d = xz_distance(main.status(), sub.status())
            if d is not None and (best is None or d < best):
                best = d
            if d is not None and d <= STEADY_MAX:
                got_close = True
                break
        except Exception:
            pass
        time.sleep(2)
    if not got_close:
        print("      ✗ 未收敛（最近距离 %s）—— 可能：目标名不对 / 两 bot 不同维度 / 被地形挡住"
              % ("%.1f" % best if best is not None else "n/a"))
        results.append(("跟随几何: 收敛", False))
        return
    print("      ✓ 已进入 %.0f 格内（最近 %.1f）" % (STEADY_MAX, best))
    results.append(("跟随几何: 收敛", True))

    # 阶段二：稳定窗口采样
    print("  · 阶段二：稳定窗口采样 %ds" % steady_sec)
    samples = []
    t = time.time() + steady_sec
    while time.time() < t:
        try:
            a, b = main.status(), sub.status()
            if not same_dim(a, b):
                print("      ✗ 采样期间两 bot 不在同一维度")
                results.append(("跟随: 同维度", False))
                break
            samples.append(xz_distance(a, b))
        except Exception:
            pass
        time.sleep(2)
    st = summarize(samples)
    print("      · 样本 %s" % json.dumps(st, ensure_ascii=False))
    if not st:
        results.append(("跟随几何: 稳态", False))
        return
    steady_ok = STEADY_MIN <= st["median"] <= STEADY_MAX and st["max"] <= STEADY_MAX * 1.5
    print("      %s（期望中位数 ∈ [%.0f, %.0f]）" % ("✓ PASS" if steady_ok else "✗ FAIL",
                                                   STEADY_MIN, STEADY_MAX))
    results.append(("跟随几何: 稳态 %s" % json.dumps(st, ensure_ascii=False), steady_ok))
    results.append(("跟随: 未超偏离上限 %.0f 格" % STRAY_LIMIT, st["max"] <= STRAY_LIMIT))


def case_follow_work_logs(sub_logs, results):
    """C4: [FOLLOW-WORK] 日志标记与放弃原因枚举。"""
    print("\n---- C4) [FOLLOW-WORK] 日志 ----")
    if sub_logs is None or sub_logs.session() is None:
        print("  [SKIP] 未提供 --gamedir-sub，或该目录下没有会话日志")
        results.append(("FOLLOW-WORK 日志", None))
        return
    take = sub_logs.grep("diag.log", r"\[FOLLOW-WORK\]")
    print("      命中 %d 条" % len(take))
    for ln in take[-6:]:
        print("      | %s" % ln.strip()[:160])
    abandon = [ln for ln in take if "放弃正事" in ln]
    bad = [ln for ln in abandon
           if not any(k in ln for k in ("单次超", "偏离", "离开加载范围"))]
    if abandon:
        print("      放弃原因 %d 条，越界 %d 条（合法原因只有：单次超 30s / 偏离 N 格 / 目标离开加载范围）"
              % (len(abandon), len(bad)))
    results.append(("FOLLOW-WORK 放弃原因合法", len(bad) == 0))


def case_sub_targets(sub_logs, results):
    """C5: 次 bot 的木头/食物目标必须是降档值（若该阶段真跑到了）。"""
    print("\n---- C5) 次 bot 目标降档（契约型：若出现则必须降档）----")
    if sub_logs is None or sub_logs.session() is None:
        print("  [SKIP] 未提供 --gamedir-sub")
        results.append(("目标降档", None))
        return

    # ① 最强判据：引导宿主自己的自述状态「[次bot引导] 补木头 X/8」——
    #    这个串只可能由 SubBotBootstrapTask 写出，故可用 tail（引导常在脚本启动前就跑起来了）。
    # ⚠️ 文案在 1006 20:5x 换过：旧「[次bot引导] 补木头 X/8」→ 新「[次bot引导] 前置(主线智能)
    # 木头 X/8 食物 Y/40」（前置改为跑主线本体）。正则必须同步，否则永远匹配不到 —— 而本用例
    # 的设计是"匹配不到 = 不判失败"，于是会**静默假阴性**（21:2x 实测踩到）。故用宽松式
    # `[^\n]*?` 锚住关键词、不绑死整段文案，日后改措辞也不会失效。
    boot = re.findall(r"\[次bot引导\][^\n]*?木头 (\d+)/(\d+)", sub_logs.tail_text("debugstate.log"))
    boot += re.findall(r"\[次bot引导\][^\n]*?食物 (\d+)/(\d+)", sub_logs.tail_text("debugstate.log"))
    if boot:
        targets = sorted({int(t) for _, t in boot})
        ok = all(t <= 60 for t in targets)     # 主 bot: 木头 25 / 食物 120
        print("      引导宿主目标出现值 %s → %s" % (targets, "✓ PASS" if ok else "✗ FAIL（未降档）"))
        results.append(("次 bot 引导目标已降档 %s" % targets, ok))
    else:
        print("      · 引导宿主自述状态未出现（未走到前置或日志不可读）→ 不判失败")

    # ② 兜底：主线自己的囤木/食物峰值标记（锚点，只在本次运行新增里找）
    wood = re.findall(r"Stockpiling wood \(logs\): (\d+)/(\d+)", sub_logs.new_text("debugstate.log"))
    wood += re.findall(r"Stockpiling wood \(logs\): (\d+)/(\d+)", sub_logs.new_text("diag.log"))
    if wood:
        targets = sorted({int(t) for _, t in wood})
        ok = all(t <= 12 for t in targets)
        print("      主线囤木目标出现值 %s → %s" % (targets, "✓ PASS" if ok else "✗ FAIL（疑似仍在用 25）"))
        results.append(("木头目标已降档 %s" % targets, ok))
    else:
        print("      · 本次运行未新增 Stockpiling wood → 不判失败")

    peak = re.findall(r"\[FoodPeak\]\s*peak reached food=(\d+)/(\d+)", sub_logs.new_text("diag.log"))
    if peak:
        targets = sorted({int(t) for _, t in peak})
        ok = all(t <= 60 for t in targets)
        print("      食物峰值目标出现值 %s → %s" % (targets, "✓ PASS" if ok else "✗ FAIL（疑似仍在用 120）"))
        results.append(("食物目标已降档 %s" % targets, ok))
    else:
        print("      · 本次运行未新增 [FoodPeak] peak reached → 不判失败")


def case_auto_bootstrap(sub, sub_logs, results, wait_sec):
    """
    C7: **全程不发任何命令**，次 bot 应自己动起来（引导前置 → 随行）。
    这是"不需要对端"这句话唯一的硬证据 —— 其他用例都要先发命令才能观测。

    判据（2026-10-06 20:0x 实测修正）：
    `/status.taskDetail` 显示的是**深层动作**（例："靠近，为击杀生猪排"），看不到
    宿主任务的类名 ⇒ 不能用 taskDetail 关键字做主判据。改用两条：
      ① 强判据：`debugstate.log` 出现 `[次bot引导]` —— 这个串只可能由 SubBotBootstrapTask
         的 setDebugState 写出，不会与别的功能撞（故可用 tail 而非锚点）；
      ② 弱判据：`/status.task` 非"空闲"，且不是主线（防止被误拉去跑 Beatmashing）。
    两者取或：① 命中直接通过；否则看 ②。
    """
    print("\n---- C7) 次 bot 自动引导（本轮不发任何命令）----")
    if sub is None:
        print("  [SKIP] 未提供 --sub")
        results.append(("自动引导", None))
        return
    try:
        s = sub.status()
    except Exception as ex:
        print("  ✗ 读 /status 失败：%s" % ex)
        results.append(("自动引导", False))
        return
    if (s.get("role") or "").strip().lower() != "sub":
        print("  [SKIP] 该实例 role=%r，不是 sub（自动引导只对次 bot 生效）" % s.get("role"))
        results.append(("自动引导", None))
        return

    # ① 强判据：宿主自述状态（tail，因为引导可能早在脚本启动前就跑起来了）
    marks = []
    if sub_logs is not None and sub_logs.session() is not None:
        marks = sub_logs.tail_grep("debugstate.log", r"\[次bot引导\]")
        marks += sub_logs.tail_grep("diag.log", r"\[SUB-BOOT\]")
    print("  · 引导标记（tail debugstate/diag）：命中 %d 条" % len(marks))
    for ln in marks[-4:]:
        print("      | %s" % ln.strip()[:150])

    # ② 弱判据：现场不空闲、且不是在跑主线
    end = time.time() + (0 if marks else wait_sec)
    task, detail = (s.get("task") or ""), (s.get("taskDetail") or "")
    while time.time() <= end:
        try:
            x = sub.status()
            task, detail = (x.get("task") or ""), (x.get("taskDetail") or "")
            if task and task != "空闲":
                break
        except Exception:
            pass
        time.sleep(3)
    busy = bool(task) and task != "空闲"
    is_mainline = "Beating the Game" in task
    print("      /status.task=%r taskDetail=%r" % (task, detail))

    ok = len(marks) > 0 or (busy and not is_mainline)
    if len(marks) > 0:
        print("      ✓ PASS（未发命令，[次bot引导] 标记已出现）")
    elif ok:
        print("      ✓ PASS（未发命令，次 bot 自己忙起来了：%r）" % task)
    else:
        print("      ✗ FAIL —— 仍空闲。检查 bridge.json 的 leader 是否非空、role 是否 sub；"
              "以及 jar 是否含入口挂载点修复（挂 UserTaskChain.onTick 的旧版永不执行）")
    results.append(("自动引导: 未发命令即启动", ok))

    if marks:
        results.append(("自动引导: 有引导标记", True))


def case_bad_leader(sub, results, timeout):
    """C6: 跟随一个不存在的目标 → 必须 failed 且带 reason（不能无限空转）。"""
    print("\n---- C6) 无效跟随目标 → failed ----")
    if sub is None:
        print("  [SKIP] 未提供 --sub")
        results.append(("无效目标 failed", None))
        return
    bogus = "__no_such_player_%d__" % int(time.time() % 100000)
    print("  [%s] @follow %s work" % (sub.name, bogus))
    try:
        code, resp = sub.post_paced("@follow %s work" % bogus)
    except Exception as ex:
        print("      ✗ HTTP 异常：%s" % ex)
        results.append(("无效目标 failed", False))
        return
    if code != 200:
        print("      ✗ HTTP %s（404 = 旧 jar；403 = 白名单；429 = 限流）" % code)
        results.append(("无效目标 failed", False))
        return
    ev = sub.wait_done(resp.get("actionId"), timeout=timeout)
    if ev is None:
        print("      ✗ %ds 内没有 action_done —— 说明它没有出口，会一直空转" % timeout)
        results.append(("无效目标 failed（有出口）", False))
        return
    d = ev.get("detail") or {}
    print("      ← status=%s reason=%r" % (d.get("status"), d.get("reason")))
    ok = d.get("status") == "failed" and bool(d.get("reason"))
    results.append(("无效目标 failed（有出口）", ok))
    if not ok:
        print("      · 期望 status=failed 且 reason 非空（失败必须说明原因）")


# ============================ main ============================

def parse_ep(s):
    if not s:
        return None
    name, _, url = s.partition("=")
    return name.strip(), url.strip()


def main():
    ap = argparse.ArgumentParser(description="随行 / 角色 验证（不需要对端）")
    ap.add_argument("--main", default="bot_1=http://127.0.0.1:8761", help="主 bot  name=url")
    ap.add_argument("--sub", default="bot_2=http://127.0.0.1:8762", help="次 bot  name=url")
    ap.add_argument("--leader", default="", help="主 bot 的 **MC 玩家名**（不是 botId）")
    ap.add_argument("--gamedir-main", default="", help="主 bot 实例目录（读日志用，可空）")
    ap.add_argument("--gamedir-sub", default="", help="次 bot 实例目录（读日志用，可空）")
    ap.add_argument("--rate", type=int, default=6, help="每 bot 每分钟非只读命令上限")
    ap.add_argument("--timeout", type=int, default=60, help="等 action_done 的秒数")
    ap.add_argument("--approach-timeout", type=int, default=120, help="等跟随距离收敛的秒数")
    ap.add_argument("--steady-sec", type=int, default=30, help="稳态采样窗口秒数")
    ap.add_argument("--bootstrap-wait", type=int, default=45, help="等次 bot 自动拉起引导的秒数")
    ap.add_argument("--skip-geometry", action="store_true", help="跳过需要两个 bot 的几何用例")
    ap.add_argument("--list", action="store_true", help="只列用例")
    args = ap.parse_args()

    cases = [
        "C1  role 字段存在且取值正确（对端据此区分主次）",
        "C2  贴 %.0f 格跟随：距离收敛" % FOLLOW_DISTANCE,
        "C3  稳态距离中位数 ∈ [%.0f, %.0f]，且全程不超 %.0f 格" % (STEADY_MIN, STEADY_MAX, STRAY_LIMIT),
        "C4  [FOLLOW-WORK] 日志：放弃原因只能是 单次超30s / 偏离N格 / 目标离开加载范围",
        "C5  次 bot 目标降档（契约型：日志里若出现囤木/食物峰值，必须是降档值）",
        "C6  跟随不存在的目标 → failed 且有 reason（证明有出口，不无限空转）",
        "C7  次 bot 自动引导：**不发任何命令**，自己进入引导/随行（role=sub 且 leader 非空时）",
    ]
    if args.list:
        for c in cases:
            print(c)
        return 0

    print("=" * 78)
    print("随行 / 角色 验证   （不需要对端，直连 localhost bridge）")
    print("=" * 78)

    main_ep, sub_ep = parse_ep(args.main), parse_ep(args.sub)
    main_bot = Bot(main_ep[0], main_ep[1], args.rate) if main_ep else None
    sub_bot = Bot(sub_ep[0], sub_ep[1], args.rate) if sub_ep else None

    live = []
    for label, b in (("主", main_bot), ("次", sub_bot)):
        if b is None:
            continue
        try:
            s = b.status()
        except Exception as ex:
            print("✗ %s(%s) 连不上 %s：%s" % (label, b.name, b.base, ex))
            b = None
            continue
        if not s.get("online"):
            print("✗ %s(%s) bridge 通了但不在游戏里（online=false）" % (label, b.name))
            b = None
            continue
        b.was_mainline = bool(s.get("mainlineProgress"))
        b.drain()
        live.append(b)
        print("✓ %s bot %s 在线  role=%s  task=%r" % (label, b.name, s.get("role"), s.get("task")))

    if not live:
        print("\n没有在线实例，退出。")
        return 2

    main_logs = GameLogs(args.gamedir_main) if args.gamedir_main else None
    sub_logs = GameLogs(args.gamedir_sub) if args.gamedir_sub else None
    for label, lg in (("主", main_logs), ("次", sub_logs)):
        if lg:
            s = lg.session()
            print("· %s bot 日志会话：%s" % (label, s if s else "（未找到，日志断言将 SKIP）"))

    # 日志锚点：开始前记偏移，之后只读新增，避免拿历史日志当通过
    for lg in (main_logs, sub_logs):
        if lg:
            lg.anchor()

    results = []
    case_role(main_bot, sub_bot, results)

    # C7 必须排在任何"发命令"的用例之前 —— 它验的就是"不发命令也会动"。
    case_auto_bootstrap(sub_bot, sub_logs, results, args.bootstrap_wait)

    if not args.skip_geometry:
        case_follow_geometry(main_bot, sub_bot, args.leader, results,
                             args.approach_timeout, args.steady_sec)
        case_follow_work_logs(sub_logs, results)
    else:
        print("\n[SKIP] --skip-geometry")
        case_follow_work_logs(sub_logs, results)

    case_sub_targets(sub_logs, results)
    case_bad_leader(sub_bot, results, args.timeout)

    # ---------- 复位 ----------
    print("\n---- 复位 ----")
    for b in live:
        c = "@resume_main" if b.was_mainline else "@idle"
        try:
            code, _ = b.post_paced(c)
            print("  [%s] %s → HTTP %s" % (b.name, c, code))
        except Exception as ex:
            print("  [%s] %s → 异常 %s" % (b.name, c, ex))

    # ---------- 汇总 ----------
    print("\n" + "=" * 78)
    real = [(n, ok) for n, ok in results if ok is not None]
    skipped = [n for n, ok in results if ok is None]
    for n, ok in real:
        print("%s  %s" % ("✓" if ok else "✗", n))
    for n in skipped:
        print("-  %s（SKIP）" % n)
    passed = sum(1 for _, ok in real if ok)
    print("=" * 78)
    print("合计：%d/%d 通过，%d 跳过" % (passed, len(real), len(skipped)))
    if passed != len(real):
        print("⚠ 有失败项。排查顺序：① 新 jar 是否已部署且客户端已重启（curl /status 看有没有 role 键）"
              "② bridge.json 是否配了 role ③ --leader 是否写的是 MC 玩家名而不是 botId")
    return 0 if passed == len(real) else 1


if __name__ == "__main__":
    sys.exit(main())
