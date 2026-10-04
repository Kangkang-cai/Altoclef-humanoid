#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bridge 冒烟测试 —— 一次性跑完本轮新增/改动的桥接命令与"回执契约"。

用途：开两个客户端进同一服务器（bridge 分别监听 8761 / 8762），在本机跑这个脚本，
      它会自动把白名单里的新命令、失败回执、/status 新字段全过一遍并给出 PASS/FAIL。

用法（Windows 举例）：
    # 最简：默认测 bot_1(8761) 与 bot_2(8762)
    C:\\Users\\KangKang\\.workbuddy\\binaries\\python\\versions\\3.13.12\\python.exe bridge_smoke_test.py

    # 只测一台
    python bridge_smoke_test.py --bots bot_1=http://127.0.0.1:8761

    # 顺带测破坏性用例（@forgetmem all，会清掉该 bot 本存档的全部自动结构记忆！）
    python bridge_smoke_test.py --destructive

    # 只看用例清单不跑
    python bridge_smoke_test.py --list

⏱ 限流提醒：bridge 默认 actionRatePerMinute=6（只读命令不占额度）。全量用例约 14 条
   非只读命令/台 ⇒ 约 2.5 分钟。若嫌慢，可临时把三处
   altoclef/configs/bridge.json 加一行 "actionRatePerMinute": 200 后重启，再
   用 --rate 200 跑（跑完记得改回 6）。

安全：默认全部用例可逆（quota 用完会 @quota all -1 复位）。结束时会按测试前的
      主线状态自动复位（之前在跑主线 → @resume_main；否则 → @idle）。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import deque

try:
    sys.stdout.reconfigure(errors="replace")
except Exception:
    pass

READ_ONLY = {"coords", "status", "inventory", "help", "list"}


class Bot:
    def __init__(self, name, base, rate_per_min):
        self.name = name
        self.base = base.rstrip("/")
        self.cursor = ""
        self.sent = deque()
        self.rate = rate_per_min
        self.was_mainline = False

    # ---------- HTTP ----------
    def _get(self, path):
        req = urllib.request.Request(self.base + path, method="GET")
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read().decode("utf-8"))

    def status(self):
        return self._get("/status")

    def post_raw(self, cmd, action_id=None):
        body = {"cmd": cmd}
        if action_id:
            body["actionId"] = action_id
        data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(
            self.base + "/command", data=data, method="POST",
            headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, {"raw": raw}

    # ---------- 限流（客户端侧自限，避免 429）----------
    def _token_of(self, cmd):
        return cmd.strip().lstrip("@").split(" ")[0].split(";")[0]

    def pace(self, cmd):
        if self._token_of(cmd) in READ_ONLY or self.rate <= 0:
            return
        while True:
            now = time.time()
            while self.sent and now - self.sent[0] >= 60:
                self.sent.popleft()
            if len(self.sent) < self.rate:
                self.sent.append(now)
                return
            wait = 60 - (now - self.sent[0]) + 0.5
            print("      · 限流中，等待 %.0fs（%d 条/分钟）" % (wait, self.rate))
            time.sleep(max(0.5, wait))

    def post_paced(self, cmd, max_retry=3):
        """pace + POST，撞到服务端 429 时按其 retryAfterSec 退避重试。

        ⚠️ 服务端限流是 **60s 滑动窗口且跨进程持续**（`ActionDispatcher.rateCheckAndRecord`），
        而这里的 pace() 只统计**本次运行**发出的命令 ⇒ 连着跑两轮、或与别的脚本同时跑，
        必然有一部分命令落在上一轮的窗口里被 429。重试是唯一的解法，不是加长等待。
        """
        code, resp = None, None
        for attempt in range(max_retry + 1):
            self.pace(cmd)
            code, resp = self.post_raw(cmd)
            if code != 429:
                return code, resp
            wait = float(resp.get("retryAfterSec") or 5) + 1.0
            print("      · 服务端限流(429)，退避 %.0fs 后重试（第 %d 次）" % (wait, attempt + 1))
            time.sleep(wait)
        return code, resp

    # ---------- 事件 ----------
    def poll(self):
        r = self._get("/events?cursor=" + urllib.parse.quote(self.cursor))
        nxt = r.get("nextCursor", "")
        if nxt:
            self.cursor = nxt
        return r.get("events", [])

    def drain(self):
        try:
            self.poll()
        except Exception:
            pass

    def wait_done(self, action_id, timeout=60):
        end = time.time() + timeout
        while time.time() < end:
            try:
                for e in self.poll():
                    if e.get("type") != "action_done":
                        continue
                    d = e.get("detail") or {}
                    if d.get("actionId") == action_id:
                        return e
            except Exception as ex:
                print("      · /events 读取异常：%s" % ex)
            time.sleep(1)
        return None

    def wait_status(self, pred, timeout=12):
        end = time.time() + timeout
        last = None
        while time.time() < end:
            try:
                last = self.status()
                if pred(last):
                    return last
            except Exception:
                pass
            time.sleep(1)
        return last


# ============================ 用例 ============================

# name, cmd, expect_status(completed/failed/None), must_contain(文本或 reason 里**命中其一**即可的候选片段)
# ⚠️ 参数缺失类用例：命令的 call() 根本不会执行（ArgParser 先拦），reason 只会有
#    "Not enough arguments supplied: ... Usage: xxx" ⇒ 命令自己写的中文串**不可达**，别写进候选。
CASES_CORE = [
    ("quota 失败：物品名认不出", "@quota no_such_item_xyz 1", "failed", ["认不出物品 id"]),
    ("quota 失败：少参数", "@quota blaze_rod", "failed", ["Not enough arguments", "Usage"]),
    ("quota 成功：设份额", "@quota blaze_rod 3", "completed", ["blaze_rod"]),
    ("quota 成功：取消份额", "@quota blaze_rod -1", "completed", ["blaze_rod"]),
    ("quota 成功：清空全部", "@quota all -1", "completed", ["清空"]),
    ("forgetmem 失败：类型认不出", "@forgetmem no_such_type", "failed", ["认不出类型"]),
    ("forgetmem 失败：少参数", "@forgetmem", "failed", ["Not enough arguments", "Usage"]),
    ("forgetmem 成功：作废某类型", "@forgetmem lava_lake", "completed", ["已作废本存档"]),
    ("sleep 失败：未知模式", "@sleep bogus_mode", "failed", ["未知模式"]),
    ("sleep 成功：熬到天亮", "@sleep wait-dawn", "completed", ["熬到天亮"]),
    ("idle：接管（解除 sleep 按住态）", "@idle", "completed", ["待命"]),
    ("resume_main：回主线", "@resume_main", "completed", ["主线"]),
]

# 环境相关：结果取决于现场（附近有没有床），只报告不断言
CASES_ENV = [
    ("sleep bed-only（现场相关：有床→completed / 没床→failed）", "@sleep bed-only", None, None),
]

# 破坏性：默认不跑
CASES_DESTRUCTIVE = [
    ("forgetmem 成功：作废全部（破坏性）", "@forgetmem all", "completed", ["已作废本存档"]),
]


def run_case(bot, name, cmd, expect, must_contain, timeout=60):
    print("  [%s] %s" % (bot.name, name))
    print("      → %s" % cmd)
    try:
        code, resp = bot.post_paced(cmd)
    except Exception as ex:
        print("      ✗ HTTP 异常：%s" % ex)
        return False
    if code != 200:
        if code == 404:
            print("      ✗ HTTP 404 unknown command —— 客户端**很可能还在跑旧 jar**（没重启？）")
        elif code == 403:
            print("      ✗ HTTP 403 not in whitelist —— 该实例 bridge.json 的 allowedCommands 未含此命令")
        elif code == 429:
            print("      ✗ HTTP 429 服务端限流未能在重试内通过（可能与别的脚本同时在打同一实例）")
        else:
            print("      ✗ HTTP %s %s" % (code, json.dumps(resp, ensure_ascii=False)))
        return False
    aid = resp.get("actionId")
    ev = bot.wait_done(aid, timeout=timeout)
    if ev is None:
        print("      ✗ 超时：%ss 内没等到 action_done（对端兜底会判失败；这里按 FAIL 记）" % timeout)
        return False
    detail = ev.get("detail") or {}
    status = detail.get("status")
    text = ev.get("text", "")
    reason = detail.get("reason", "")
    shown = "status=%s | %s%s" % (status, text, (" | reason=" + reason) if reason else "")
    print("      ← %s" % shown)

    ok = True
    if expect is not None and status != expect:
        print("      ✗ 期望 status=%s，实际 %s" % (expect, status))
        ok = False
    if expect == "failed" and not reason:
        print("      ✗ 期望带 detail.reason（失败必须给原因）")
        ok = False
    if must_contain:
        # ⚠️ 多个片段 = "命中其一即可"（候选），**不是**"全部必须出现"。
        # (2026-10-04 修) 此前 failed 分支用的是 all() 语义，与 else 分支的 any() 不一致
        # ⇒ 多候选用例（forgetmem 少参数）会假失败。
        if not any(s in (text + " " + reason) for s in must_contain):
            print("      ✗ 期望文本/reason 含其一 %r" % must_contain)
            ok = False
    print("      %s" % ("✓ PASS" if ok else "✗ FAIL"))
    return ok


def check_status_fields(bot, results):
    print("  [%s] /status 字段检查" % bot.name)
    try:
        s = bot.status()
    except Exception as ex:
        print("      ✗ 读取失败：%s" % ex)
        results.append((bot.name + " /status", False))
        return
    online = s.get("online")
    pos = s.get("pos") or {}
    print("      online=%s dim=%s pos=(%s,%s,%s) timeOfDay=%s" % (
        online, pos.get("dim"), pos.get("x"), pos.get("y"), pos.get("z"), s.get("timeOfDay")))
    print("      onlinePlayers=%r sleepingPlayers=%r" % (
        s.get("onlinePlayers"), s.get("sleepingPlayers")))
    print("      mainlineProgress=%r" % s.get("mainlineProgress"))
    if s.get("activeAction"):
        print("      activeAction=%s" % json.dumps(s["activeAction"], ensure_ascii=False))
    # timeOfDay 必须存在（睡觉协调要用）
    ok = bool(online) and bool(s.get("timeOfDay"))
    if not s.get("onlinePlayers"):
        print("      · 提示：onlinePlayers 为空（单人服/对端未在线 → 该键按约定省略）")
    print("      %s" % ("✓ PASS" if ok else "✗ FAIL（online/timeOfDay 必须有值）"))
    results.append((bot.name + " /status 基础字段", ok))
    return s


def check_active_action_is_sleep(bot, results):
    print("  [%s] /sleep 后 activeAction.cmd 应为 sleep" % bot.name)
    s = bot.wait_status(lambda x: (x.get("activeAction") or {}).get("cmd") == "sleep")
    aa = (s or {}).get("activeAction") or {}
    ok = aa.get("cmd") == "sleep"
    print("      activeAction=%s" % json.dumps(aa, ensure_ascii=False))
    print("      %s" % ("✓ PASS" if ok else "✗ FAIL"))
    results.append((bot.name + " activeAction=sleep", ok))


def main():
    ap = argparse.ArgumentParser(description="bridge 冒烟测试")
    ap.add_argument("--bots", default="bot_1=http://127.0.0.1:8761,bot_2=http://127.0.0.1:8762",
                    help="逗号分隔 name=url")
    ap.add_argument("--rate", type=int, default=6, help="每 bot 每分钟非只读命令上限（默认 6，与 bridge 一致）")
    ap.add_argument("--timeout", type=int, default=60, help="等待 action_done 的秒数")
    ap.add_argument("--destructive", action="store_true", help="额外跑破坏性用例（@forgetmem all）")
    ap.add_argument("--list", action="store_true", help="只列用例")
    args = ap.parse_args()

    if args.list:
        for n, c, e, m in CASES_CORE + CASES_ENV + CASES_DESTRUCTIVE:
            print("%-48s %-28s %s" % (n, c, e or "-"))
        return 0

    bots = []
    for part in args.bots.split(","):
        part = part.strip()
        if not part:
            continue
        name, _, url = part.partition("=")
        bots.append(Bot(name.strip(), url.strip(), args.rate))
    if not bots:
        print("没有可用 bot")
        return 2

    print("=" * 78)
    print("bridge 冒烟测试    bots=%s    rate=%d/min" % (",".join(b.name for b in bots), args.rate))
    print("=" * 78)

    results = []
    live = []
    for b in bots:
        try:
            s = b.status()
        except Exception as ex:
            print("✗ %s 连不上 %s：%s（bridge 没启动 / 端口不对？）" % (b.name, b.base, ex))
            results.append((b.name + " 连通", False))
            continue
        if not s.get("online"):
            print("✗ %s bridge 通了但 bot 不在游戏里（online=false），跳过其用例" % b.name)
            results.append((b.name + " 在线", False))
            continue
        b.was_mainline = bool(s.get("mainlineProgress"))
        b.drain()
        live.append(b)
        print("✓ %s 在线（测试前主线在跑=%s）" % (b.name, b.was_mainline))

    if not live:
        print("\n没有任何在线的 bot，退出。")
        return 2

    print("\n---- 前置条件自检（本轮新命令都不吃物品）----")
    print("  · 需要：两客户端都已进世界(online=true) + 同一服务器 + 已部署新 jar 并重启")
    print("  · 不需要：任何背包物品 —— @quota 只写份额/@forgetmem 只改记忆/@sleep wait-dawn 不要床")
    print("  · 唯一例外：@sleep bed-only 会查【手里有床 或 24 格内有床方块】，没有→failed（属正常）")
    try:
        s0 = live[0].status()
        tod = (s0.get("timeOfDay") or "").strip()
        print("  · 当前 timeOfDay=%s%s" % (tod, "（白天 ⇒ 睡眠保持态断言会 SKIP，见 §2）"
                                           if tod and tod != "night" else ""))
    except Exception:
        pass

    print("\n---- 1) 核心用例（回执 + 失败原因）----")
    for n, c, e, m in CASES_CORE:
        results.append((n, run_case(live[0], n, c, e, m, args.timeout)))

    print("\n---- 2) /status 与现场状态 ----")
    first_status = check_status_fields(live[0], results)
    tod = ((first_status or {}).get("timeOfDay") or "").strip()
    if tod == "night":
        # sleep 已在核心用例里下过；这里再下一次专门验 activeAction（会占限流额度）
        results.append(("activeAction=sleep", run_case(live[0], "补一发 @sleep wait-dawn", "@sleep wait-dawn",
                                                      "completed", ["熬到天亮"], args.timeout)))
        check_active_action_is_sleep(live[0], results)
    else:
        print("  [SKIP] activeAction=sleep —— 当前 timeOfDay=%r，不是 night。" % tod)
        print("         原因：WaitForDawnTask 与 SleepThroughNightTask 的 isFinished 判据都是"
              "【白天=完成】⇒ 白天下发后一个 tick 就结束了，保持态留不住（不是 bug）。")
        print("         夜里重跑即可验；此处改用 @idle 验同一套保持态机制（与时段无关）：")
        results.append(("activeAction=idle", run_case(live[0], "@idle 后 activeAction.cmd 应为 idle",
                                                     "@idle", "completed", ["待命"], args.timeout)))
        st = live[0].wait_status(lambda x: (x.get("activeAction") or {}).get("cmd") == "idle")
        aa = (st or {}).get("activeAction") or {}
        ok = aa.get("cmd") == "idle"
        print("      activeAction=%s → %s" % (json.dumps(aa, ensure_ascii=False), "✓ PASS" if ok else "✗ FAIL"))
        results.append(("activeAction=idle", ok))

    print("\n---- 3) 环境相关用例（只报告，不断言）----")
    for n, c, e, m in CASES_ENV:
        run_case(live[0], n, c, None, None, args.timeout)

    if args.destructive:
        print("\n---- 4) 破坏性用例（--destructive）----")
        for n, c, e, m in CASES_DESTRUCTIVE:
            results.append((n, run_case(live[0], n, c, e, m, args.timeout)))

    if len(live) > 1:
        print("\n---- 5) 第二实例（bot_2）：证明每实例独立 + 在线玩家互见 ----")
        results.append(("bot_2 quota 失败链路", run_case(live[1], "quota 失败：物品名认不出",
                                                      "@quota no_such_item_xyz 1", "failed",
                                                      ["认不出物品 id"], args.timeout)))
        sq = check_status_fields(live[1], results)
        if sq and first_status:
            # 注意：onlinePlayers 里是 **MC 用户名**（如 qiaoqiao/kangkang），不是 bridge 的 botId，
            # 所以不能拿 botId 去比对。判据改为：两边看到的名单一致 且 公共玩家 ≥2
            # （同一服务器且都在线时，必然互相看见）。
            op1 = set(x for x in (first_status.get("onlinePlayers") or "").split(",") if x)
            op2 = set(x for x in (sq.get("onlinePlayers") or "").split(",") if x)
            common = op1 & op2
            mut = (op1 == op2) and len(common) >= 2
            print("      · 互见检查：%s 看到 %s / %s 看到 %s" % (
                live[0].name, sorted(op1), live[1].name, sorted(op2)))
            print("      · 名单一致=%s 公共玩家=%s → %s" % (
                op1 == op2, sorted(common), "✓ PASS" if mut else "✗ FAIL"))
            if not mut:
                print("        若两 bot 不在同一服务器/经端口转发/不同机器，此项应忽略。")
            results.append(("两 bot 在线名单互见", mut))

    # ---------- 复位 ----------
    print("\n---- 6) 复位（可逆用例收尾）----")
    for b in live:
        for c in ("@quota all -1", "@idle" if not b.was_mainline else "@resume_main"):
            try:
                code, resp = b.post_paced(c)
                print("  [%s] %s → HTTP %s" % (b.name, c, code))
            except Exception as ex:
                print("  [%s] %s → 异常 %s" % (b.name, c, ex))

    # ---------- 汇总 ----------
    print("\n" + "=" * 78)
    passed = sum(1 for _, ok in results if ok)
    for n, ok in results:
        print("%s  %s" % ("✓" if ok else "✗", n))
    print("=" * 78)
    print("合计：%d/%d 通过" % (passed, len(results)))
    if passed != len(results):
        print("⚠ 有失败项。排查顺序：① jar 是否已部署（curl /status 看有没有 onlinePlayers/activeAction 键）"
              "② bridge.json 的 allowedCommands 是否含新命令 ③ 客户端是否重启过")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
