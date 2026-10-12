# 对端命令回执使用细则（定稿 2026-10-12）

> 面向 chat_and_play 对端：AI 角色下发 `@命令` 后，如何知道"命令被收到没有、在推进吗、做完没有、做不了为什么"。
> 本文件是注入对端 AI 上下文的权威依据；实现落点全部标注了代码位置，便于联合调试。
> 契约版本标记：`receipts-v2`（v2 = 1012 新增 action_progress 心跳、deathDrops、pickup 补链、follow 站定豁免）。

---

## 0. 三条回执通道（先分清）

| 通道 | 内容 | 特点 |
|---|---|---|
| **HTTP 同步响应** | 命令收不收（参数错/白名单外/限流） | **只在 POST /command 的响应里**，不进事件流。对端必须检查 HTTP 状态码 |
| **事件流 GET /events** | action_accepted / action_progress / action_done / death / bot_stuck … | 异步推送（轮询游标），跨时间的状态变化 |
| **状态快照 GET /status** | activeAction（当前行动实时现场）/ deathDrops / botStatus / task … | 每次查询都是全量当下，5s 刷新一次快照 |

**铁律：一条命令的生命期 = `action_accepted` → （0 条或多条 `action_progress`）→ `action_done`。**
对端 AI 判断"我下的命令怎么样了"只看这三步；`done` 不到就是还在跑或已失联（见 §6 出口兜底）。

---

## 1. 命令面全景（21 条）

白名单见 `BridgeConfig.allowedCommands`（默认 17 条）+ 主线干预伪命令 2 条。按回执行为分三类：

### 1.1 任务型（有终点，全链回执）—— goto / follow / give / get / equip / **pickup**

```
action_accepted（受理）
   ↓
action_progress（心跳）×N   ← phase=running 每 30s 一条；running↔blocked 翻转立即一条
   ↓
action_done（终态）         ← status ∈ completed / failed / cancelled / interrupted
   ↓
（若 done≠failed/interrupted）自动回主线
```

- `action_done` detail：`{"actionId","cmd","status","reason"?}`；`text` 是中文现场（如 `完成：<Collecting 32 gold.>，已自动回主线`、`做不到：目标不在加载范围`）。
- **follow 专项**：completed = **5 分钟跟随期满且目标仍在线**（不是"走到即结束"）；目标不在线/不在加载范围 = failed `做不到：目标不在加载范围`（跨维度另有专门文案）。对端文案不要把 follow completed 写成"已到达"。
- **equip 专项**：回执 text 是逐件结果（穿上/跳过原因）；0 件穿上 → failed `背包无现成护甲, 且现有材料不足以制作`。
- **get 专项**：无硬失败出口——长时间无进展只报 `blocked`（15s 起），不会自动 failed；对端看到长期 blocked 自行决策换指令。
- **pickup 专项（1012 补齐）**：此前不在回执链（accepted 后失联），现已与 get 同口径：背包计数即进度，捡到即涨。
- **goto 专项**：持续 120s 算不出路径 → 自动 failed `做不到，放弃：<原因>`。

### 1.2 按住型（无自然终点，受理即回 done）—— idle / sleep

- 受理后**立即**回一条 `action_done(status=completed)`，语义="已进入该状态"，**不是"已完成"**。
- **idle 带秒参**（`@idle 5`）：到时自动结束再回第二条 done `待命 5 秒结束（原地站立），已自动回主线`。
- **sleep**：睡到天亮/设完重生点再回第二条 done `已醒来（天亮）`/`重生点已设置（白天设点，未睡）`。
- 即：按住型 = 最多两条 done（进入态 + 自然结束）。无限期 idle 没有第二条。
- 按住态被新命令顶替：**静默清除**，无 cancelled 回执（它本无终点）。

### 1.3 瞬时型（受理即完成）—— coords / status / inventory / remember / forget / quota / forgetmem / share / autorecord / help / list

- 一条 `action_done(status=completed, text=命令反馈)`，无 pending、无心跳。
- **quota 语义**：设置的是"本 bot 对某物品的绝对持有目标"，只影响**正在跑的该物品收集任务**与后续收集决策；主线没在收该物品时设置 = 写入成功但无可见行为变化（1011 实测：blaze_rod=1 存活了 11 秒、期间主线在收金，debugstate 无 Collect 变化——**不是没生效，是没机会表现**）。

### 1.4 主线干预伪命令 —— pause_main / resume_main（需配置 enablePauseResume，默认开）

- `@pause_main`：停掉手头用户任务（含主线），**幂等**——没任务在跑回 `手头没有任务，无需暂停`（tasks.log 零痕迹，这是正常现象）。被停掉的**任务型**行动会收到 `action_done(status=interrupted, reason=被 @pause_main 暂停)`。
- `@resume_main`：回主线，回 `已回到主线`（主线本来在跑则回 `主线已在推进`）。
- pause 期间"不动"是设计意图，LivenessDigest 不报 stuck。

---

## 2. 事件契约（/events 消费口径）

| type | strong | 何时发 | detail |
|---|---|---|---|
| `action_accepted` | no | 命令过白名单+限流后 | `{actionId, cmd, args?}` |
| `action_progress` | no | 任务型行动：phase 翻转立即 / running 每 30s | `{actionId, cmd, phase, detail?, sinceSec}` |
| `action_done` | no | 终态到达（四态见 §3） | `{actionId, cmd, status, reason?}` |
| `death` | yes | 死亡边沿（带死因，最多等 2s 播报包） | — |
| `death`（跟进，no） | no | 复活 1s 后：掉落结论 | — |
| `bot_stuck` | yes | 位置+背包+任务签名 30s 全不变 | `{actionId, cmd}`（空串=不是对端指令卡的） |
| `bot_recovered` | no | stuck 后 5s 内有三元组任一变化 | — |
| `collected` / `rare_item` / `help` / `sleep` 等 | - | 背包增量白名单/求助聊天/睡失败 | — |

**death 跟进事件三种文案（1012 新增）**：
1. `死亡掉落：铁剑x1、面包x3（已记录，可去死处回收）` → `/status.deathDrops` = 清单；
2. `服务器开着死亡不掉落（keepInventory），物品已全部保留：…` → `deathDrops` = 空（=没掉）；
3. `复活完成：死亡时背包是空的，没有物品掉落` → `deathDrops` = 空。

**bot_stuck 豁免名单（这些"不动"不会报 stuck）**：开 GUI、睡觉、游戏暂停、离线、无任务、@idle/@pause_main 按住态、**follow 贴身站定**（1012 新增——此前铁头贴人 2.7 格站定 60s 被误报）。注意：follow 的"目标太远等坐标"等待**仍会**报 stuck——那是真停滞，需要你们决策。

---

## 3. action_done.status 四态（稳定契约）

| status | 含义 | 触发 |
|---|---|---|
| `completed` | 正常到达终态 | 任务跑完 / 瞬时命令执行完 / 按住态进入或自然结束 / follow 5min 期满 |
| `failed` | 确定做不到 | 参数错（CommandException）/ 命令语义失败（give 物品不在背包等）/ goto 算不出路超 120s / follow 目标始终不在线超 180s / equip 0 件穿上 |
| `cancelled` | 被新命令顶替 | 任务型行动在跑时又下了一条会顶任务的命令：`被 @X 顶替` |
| `interrupted` | 被 pause 主动叫停 | `被 @pause_main 暂停`（终态，resume 不会自动续跑旧行动，需重下） |

`reason` 仅在需要解释时出现。四值全部来自固定字面量（ActionDispatcher emitDone 各调用点），可 switch。

---

## 4. /status 关键字段（对端渲染/变化键口径）

| 字段 | 说明 |
|---|---|
| `activeAction` | `{actionId, cmd, phase(running/blocked), detail, sinceSec}`；无活跃行动**整键省略** |
| `activeAction.phase` | `blocked` = 进度计数器（key 变化）≥15s 不变；`running` = 在推进 |
| `deathDrops` | **固定输出**，空串=没掉（keepInventory 已开/没带东西/没死过） |
| `botStatus` / `stuckReason` | ok / stuck / recovering |
| `inventory` | @give 认的**原名**清单（发 @give 照抄这里，别凭记忆写 ID） |
| `keyItems` / `food` / `equipment` / `holding` | 中文名摘要（背包冻结窗口内沿用上一个有效值，最长 5s） |

**背包冻结注意**：维度切换/死亡掉落会让背包读数骤降，模组侧冻结 5s——期间 inventory 系字段是**上一个有效值**（死亡场景=死前的包）。复活后注入上下文请等 ≥6s。

---

## 5. 对端必须处理的同步错误码（POST /command，不进事件流）

| 码 | 含义 | AI 应对 |
|---|---|---|
| 400 | body/cmd 缺失、无前缀 | 修命令格式 |
| 403 | 命令不在白名单 | 换允许的命令 |
| 404 | 未知命令（含拼错） | 换命令 |
| 429 | 限流（行动类 6 条/分钟，只读不占额） | 按 `retryAfterSec` 退避 |
| 503 | 命令执行器未就绪（刚启动/切场景） | 稍后重试 |

收到 4xx/5xx 时**不会有** action_accepted/action_done——不要等回执，直接按错误处理。

---

## 6. 出口兜底与已知局限（对端超时约定）

1. **pending 关联窗口 5 分钟**（PENDING_TTL）：done 因故没关联上就丢弃——对端按 actionId 做**本地超时**（建议：goto/follow/give/get/equip/pickup ≥10min 超时；到时重查 /status.activeAction 确认真状态再重派）。
2. **同 token 顶替**：同命令再次派发时旧 pending 立即作废并回 cancelled——别把旧 actionId 的超时当异常。
3. **pending 队列上限 8 条**：溢出丢最老（正常使用碰不到）。
4. **掉线/切世界无终态事件**：行动进行中 bot 掉线，pending 不会回 done（已知局限）——对端超时兜底 + 重新上线后 activeAction 键消失即为佐证。
5. **get/equip/pickup 无自动 failed**：blocked 会一直挂着，需对端决策。
6. **心跳只覆盖任务型六命令**：按住型/瞬时型无心跳（它们的 done 早已同步回）。

---

## 7. 可直接注入 AI 角色上下文的速查段

```text
【命令回执】我发的每条 @命令：先收到 action_accepted 才算受理；任务型命令
（goto/follow/give/get/equip/pickup）每 30s 或状态翻转收 action_progress，
结束后收 action_done。done.status 四态：completed=正常结束；failed=做不到
(reason 里有原因)；cancelled=被我的新命令顶替了；interrupted=被 @pause_main 叫停。
follow 的 completed=跟随 5 分钟期满自动结束，不是"已走到"。get/equip/pickup 不会
自动 failed，长期 blocked(>15s 无进展)时要我自己决定换指令。HTTP 返回 4xx/5xx=
命令根本没受理（限流 429 按 retryAfterSec 等待），不会有任何事件。
bot 死亡会收到 death 事件 + 1 秒后掉落结论；/status.deathDrops 空=没掉东西
（服务器可能开了死亡不掉落，事件里会说）。botStatus=stuck 时 detail 里的
actionId 为空表示不是我下的指令卡的。等不到 done 就查 /status.activeAction：
键消失=行动已不在跑；还在=继续等或换指令。
```

---

## 8. 1012 自查结论（本次修订内容）

- **已修**：`@pickup` 此前不在任何回执集合（accepted 后永久失联、无 done、不顶替旧行动、无进度）——已补进任务型集合，pending/done/supersede/心跳/进度探针全链生效（`TASK_ACTIONS`+`probePickup`，ActionDispatcher.java）。
- **已修**：follow 贴身站定误报 bot_stuck（FollowPlayerTask.isStationaryByDesign → LivenessDigest 豁免）。
- **已加**：action_progress 心跳、death 跟进掉落事件、/status.deathDrops。
- **确认稳定**：action_done 四态固定字面量。
- **未改（有意保留）**：掉线无终态事件（超时兜底）；sleep/idle 双 done 设计；@sleep skip 无回执（行为开关）。
