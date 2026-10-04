# 桥接命令对接手册（AI 行动通道）

> 面向 **chat_and_play（对端）**。2026-10-03 版。
> 代码 commit `a52ca1d6`；jar md5 `a0996d520abf4ade29cf9b9047ad8fe2`。
> 变更报告（为什么加这些）见同目录 `REPORT_coordination_primitives_20261003.md`。

---

## 0. 一分钟上手

| 端点 | 用途 |
|---|---|
| `POST /command` `{"cmd":"@quota blaze_rod 3","actionId":"a-1"}` | 派发一条命令 |
| `GET /status` | 读快照（含感知字段） |
| `GET /events?cursor=<上次的 nextCursor>` | 读事件流（含行动回执） |

三条要点：
1. **每条命令都有两级回执**：`action_accepted`（受理）→ `action_done`（结果，**带 `status` 与 `reason`**）。
2. **失败一定会说为什么**（`action_done.status = "failed"`，`detail.reason` = 中文原因）。
3. 只读命令（`coords` / `status` / `inventory`）**不占限流额度**；其余 **6 条/分钟**（滑动窗口）。

---

## 1. 通用约定

- **命令前缀**：`@`（`cmd` 里要带）。
- **`actionId`**：对端可自带（用于关联），不带则由 bridge 生成 `a-<millis>-<seq>`；响应与后续回执都用它。
- **响应码**：

| 码 | 含义 |
|---|---|
| 200 | 已受理（**不代表执行成功**，看 `action_done`） |
| 400 | body 缺 `cmd` / 无前缀 / 空命令 |
| 403 | 不在白名单（`reason` 里给命令名） |
| 404 | 未知命令 |
| 429 | 限流（`retryAfterSec` = 建议退避秒数） |
| 503 | 命令执行器未就绪（还没进世界） |

- **两种完成模型**（决定你何时拿到 `action_done`）：

| 类型 | 命令 | 完成时机 |
|---|---|---|
| **瞬时** | `coords` `status` `inventory` `remember` `forget` `quota` `forgetmem` | 受理后**立即**发 `action_done` |
| **按住·无终态** | `idle` | 受理后立即发 `action_done`（= "开始保持"），**不会再有第二条**；由下一条命令或 `@resume_main` 解除 |
| **按住·有终态** | `sleep` | 受理后立即发 `action_done`（= "已开始睡/熬"）；**天亮时再发一条 `completed`**；被顶替 = `cancelled`，被暂停 = `interrupted` |
| **有任务** | `goto` `follow` `give` | 顶层任务**结束时**发 `action_done`（`status` = completed / failed / cancelled） |

> ⚠️ 对端**仍应设超时兜底**（建议 `goto` 60s / `give` 300s / 其余 120s）。有任务类在"被新命令顶替"时会立刻收到 `cancelled`；但若 bridge 与客户端断连就不会有事件。

---

## 2. 命令总表（白名单 12 条）

| 命令 | 语法 | 语义 | 类型 |
|---|---|---|---|
| `goto` | `@goto <x y z\|x z\|y\|维度>` | 导航到坐标/维度 | 有任务 |
| `follow` | `@follow <玩家名>` | 跟随某人 | 有任务 |
| `give` | `@give <玩家名> <物品> <数量>` | 给某人物品（**背包没有会去收集**） | 有任务 |
| `idle` | `@idle` | 站住待命（按住态） | 按住态 |
| `coords` | `@coords` | 报坐标（只读） | 瞬时 |
| `status` | `@status` | 报当前任务（只读） | 瞬时 |
| `inventory` | `@inventory [物品]` | 查背包（只读） | 瞬时 |
| `remember` | `@remember <名称> <x y z> [维度] [来源]` | 记一条**命名地标** | 瞬时 |
| `forget` | `@forget <名称>` | 删一条**命名地标** | 瞬时 |
| **`sleep`** | `@sleep [auto\|bed-only\|wait-dawn]` | **睡觉三态**（新） | 按住态 |
| **`quota`** | `@quota <item_id> <count>` | **需求份额**（新） | 瞬时 |
| **`forgetmem`** | `@forgetmem <type\|all>` | **作废自动结构记忆**（新） | 瞬时 |

主线干预伪命令（不在上表，走同一入口）：`@pause_main` / `@resume_main`。

---

## 3. 本次新增命令详解

### 3.1 `@sleep [mode]` —— 睡觉三态

| mode | 语义 | 何时结束 |
|---|---|---|
| `auto`（缺省） | 有床用床，没床**就地找/造** | 天亮 / 新指令 |
| `bed-only` | **只用现成的床**：手里有床 或 24 格内有已知床方块才启动 | 天亮 / 新指令 |
| `wait-dawn` | **不睡**，站桩熬到天亮 | 天亮 / 新指令 |

- 为什么：主线原版在没床时会去 96 格内找床 / 羊毛+木板造床 = **时长不可预估的远征**。`bed-only` 给你"要么就近睡，要么直说做不到"。
- `wait-dawn` 是**多人凑不齐全员入睡**时的正解（躺着等天亮 == 站着等天亮，但不会被钉在床边）。
- 起床不需要命令：任何新行动（`@idle` / `@goto` …）会顶掉它。
- 回执（**三条，别只看第一条**）：
  - 受理即 `action_done(completed)` = "已开始睡 / 已开始熬"，`text` 是中文现场；`/status.activeAction` 显示 `cmd="sleep"`；
  - **天亮自然结束** → 再发一条 `action_done(completed)`「已醒来（天亮），已自动回主线」（与 `goto`/`follow`/`give` 同款）；
  - **被新指令顶替** → `action_done(cancelled)`；**被 `@pause_main`** → `action_done(interrupted)`。
  - ⇒ 对端永远能区分"还在睡"与"已结束 / 被打断"，不会卡在等超时。
- **失败**：`bed-only` 附近没床 → `failed`；未知 mode → `failed`（旧版会静默当 `auto`，已修）。

### 3.2 `@quota <item_id> <count>` —— 需求份额

**语义 = 本 bot 对该物品的"绝对持有目标"**。设了 = 只负责凑够这个数；没设 = 走主线默认口径。

| 用法 | 含义 |
|---|---|
| `@quota blaze_rod 3` | 我只负责 3 根烈焰棒（凑够就停） |
| `@quota blaze_rod 0` | 烈焰棒归别人，我一点不碰 |
| `@quota ender_eye 0` | **总份额归零**：整条末影之眼线全停（棒/粉/珍珠都不触发） |
| `@quota blaze_rod -1` | 取消该物品份额，回主线默认 |
| `@quota all -1` | 清空全部份额 |

- 支持三条**独立**需求线：`blaze_rod` / `ender_pearl` / `ender_eye`（总份额）。物品 id 写 `blaze_rod` 或 `minecraft:blaze_rod` 都行。
- 只影响**收集**，不改传送门插框与结构搜索。
- ⚠️ 跨世界/跨会话**保留**，需要复位就 `@quota all -1`。
- ⚠️ 裸 `@quota blaze_rod 0` 单用 → 该 bot 会退化成"只收珍珠"；要彻底不做这块，**同时** `@quota ender_eye 0`。
- **背景**：主线每个 bot 各自把 `targetEyes(16)` 折算成 8 根棒 ⇒ 3 个 bot 最多收 24 根，而团队只需 ≈8 根。`@quota` 就是用来分摊的。
- 回执 `text` 会**回显 原值 → 新值**，便于确认。

### 3.3 `@forgetmem <type|all>` —— 作废自动结构记忆

bot 会自动记住"亲眼确认过"的结构中心（跨会话落盘）。记忆记错时，此前**没有任何手段作废** —— 这个命令补上。

| 用法 | 含义 |
|---|---|
| `@forgetmem stronghold` | 作废本存档的**要塞**记忆（丢眼确认的走廊坐标） |
| `@forgetmem end_portal` | 作废**末地门框**记忆 |
| `@forgetmem all` | 作废本存档**全部类型** |

类型全集：`village` `ruined_portal` `nether_fortress` `blaze_spawner` `warped_forest` `stronghold` `end_portal` `lava_lake`（大小写不敏感）。
生效范围 = **当前 `存档:维度`**，会同步重写落盘文件（否则重启复活）。与 `@forget`（命名地标）**互不干扰**。
回执 `text` 会报**清掉了几条**（0 条时说明"本来就没有，未改动"）。

---

## 4. 回执字段与"调试信息"（重点）

### 4.1 事件样例

```json
// 受理（同步，立即）
{"id":123,"bot_id":"bot_1","ts":"...","type":"action_accepted",
 "text":"[a-1] 已受理：@quota blaze_rod 3","strong":false,
 "detail":{"actionId":"a-1","cmd":"quota","args":"blaze_rod 3"}}

// 成功（瞬时命令 = 立即；有任务命令 = 任务结束时）
{"type":"action_done","text":"[a-1] quota：blaze_rod → 3 个（本 bot 的绝对持有目标）；原值 未设",
 "detail":{"actionId":"a-1","cmd":"quota","status":"completed"}}

// 失败（一定会带 reason）
{"type":"action_done","text":"[a-2] bed-only 做不到：手里没有床、附近 24 格也没有现成的床；按约定不去远行找/造。",
 "detail":{"actionId":"a-2","cmd":"sleep","status":"failed",
           "reason":"bed-only 做不到：手里没有床、附近 24 格也没有现成的床；按约定不去远行找/造。"}}
```

### 4.2 `detail` 字段

| 字段 | 出现时机 | 说明 |
|---|---|---|
| `actionId` | 总是 | 关联用 |
| `cmd` | 总是 | 命令名（token） |
| `args` | `action_accepted` | 命令参数原样回显（**本次新增**） |
| `status` | `action_done` | `completed` / `failed` / `cancelled` / `interrupted` |
| `reason` | 仅需说明时 | 人类可读原因（**失败必带**） |

`status` 取值语义：

| status | 含义 |
|---|---|
| `completed` | 干完了（瞬时命令 = 已生效；有任务命令 = 顶层任务正常结束） |
| `failed` | **做不到**（原因见 `reason`） |
| `cancelled` | 被新命令顶替 |
| `interrupted` | 被 `@pause_main` 暂停 |

### 4.3 什么情况会 `failed`（本次补全的部分）

| 命令 | 触发条件 | 你能收到的 `reason`（示例） |
|---|---|---|
| `sleep` | `bed-only` 且附近无床 | "bed-only 做不到：手里没有床、附近 24 格也没有现成的床；按约定不去远行找/造。" |
| `sleep` | mode 拼错 | "未知模式 'xxx'；可用 auto \| bed-only \| wait-dawn。" |
| `quota` | 物品 id 认不出 | "认不出物品 id —— xxx（示例：blaze_rod / ender_pearl / ender_eye）" |
| `quota` | 少参数 | "Not enough arguments supplied: You supplied 1. Usage: quota [item] [count]" |
| `forgetmem` | 类型认不出 | "认不出类型 —— xxx（可选：village\|…\|lava_lake / all）" |
| `forgetmem` | 少参数 | "缺 type 参数。用法 @forgetmem \<type\|all\>" |
| `give` | **背包没有该物品、也没法收集** | "做不到：背包里没有 xxx，也没有能收集它的方法（物品名可能拼错）。" |
| `give` / `follow` | 没给玩家名且无 butler 用户 | "做不到：没有指定玩家名，而当前没有 butler 用户可代指。……" |
| 任意 | 参数解析异常 | 解析器原文 + `Usage: <命令语法>` |
| 任意 | 派发期异常 | "命令派发异常" |

> **这条最要紧**：`@give` 在"物品不在背包"时**不会起任务**，旧版会让 `action_done` **永远不来**、你只能等超时。
> 现在它会立刻回 `failed` + 原因。**请把这段当"命令结果"消费，而不是只看 HTTP 200。**

### 4.4 `@remember` / `@forget` 的失败不看 `action_done`

这两条有自己的专用事件（`landmark_saved` / `landmark_failed`，`detail` 带 `{name,pos,dim,status,reason}`）。
`action_done` 只表示"命令受理了"。**判断成败请用它们自己的事件。**

---

## 5. `/status` 感知字段（本次新增两个）

| 字段 | 类型 | 语义 |
|---|---|---|
| `onlinePlayers` | string | 服务器在线玩家名（tab），逗号分隔；空则整键省略 |
| `sleepingPlayers` | string | **可见范围内**正在睡的玩家名，逗号分隔；无人睡则整键省略 |
| `activeAction` | object | `{actionId, cmd, phase, detail, sinceSec}`；无活跃行动则省略 |
| `mainlineProgress` | string | 如 `下界·烈焰棒2/8`；主线没跑则省略 |
| `timeOfDay` | string | `day` / `night` |
| `landmarks` | string | 命名地标 + 自动中心的 JSON 片段 |

> ⚠️ `sleepingPlayers` 只统计**已加载实体**的玩家（第三方服默认**48 格**追踪范围外拿不到姿态）。
> 它是"可见范围内谁在睡"，**不是服务器全量睡况**。判断"是否全员入睡"要意识到这个盲区。

---

## 6. 编排示例

**3 bot 分摊末影之眼（避免各刷 8 根烈焰棒）**
```
bot_1: @quota blaze_rod 8      @quota ender_eye 0     # 专攻烈焰棒
bot_2: @quota ender_pearl 18   @quota ender_eye 0     # 专攻末影珍珠
bot_3: @quota all -1                                  # 回默认口径（兜底/总装）
```

**一起睡觉（多人同步）**
```
1. 轮询各 bot /status：timeOfDay == "night"
2. 读 sleepingPlayers / onlinePlayers —— 看还差谁
3. 对未入睡的 bot 发 @sleep auto（或 bed-only 若已备床）
4. 继续轮询直到 sleepingPlayers 覆盖全部 onlinePlayers（或天已亮）
```

**记忆记错了**
```
@forgetmem stronghold      # 作废本存档要塞记忆，让 bot 重新定位
```

---

## 7. 部署状态

commit `c52b5f4e`；jar md5 `e5b4494e62514f593b7eabb154b34c4c`；**已部署**三处 mods（+ `build/libs` 四方一致，备份 `altoclef-0.4.0.jar.bak_20261004_1149`×3）。
白名单默认 **12 条**；三处 `bridge.json` 都没有 `allowedCommands` 字段 ⇒ 自动生效，**不需要改配置**。

> 主线干预伪命令 `@pause_main` / `@resume_main` 的 `action_done` 同样带 `status`（`completed`），与上表口径一致。

---

## 8. 冒烟测试脚本（`bridge_smoke_test.py`）

一次性跑完本手册里的命令与回执契约，跑完给 PASS/FAIL 汇总。

**运行前提（不需要任何背包物品）**

| 项 | 要求 | 不满足会怎样 |
|---|---|---|
| 客户端 | 两个都**已进世界**（`/status.online == true`） | 停在标题界面 → 脚本报"bridge 通了但不在游戏里"并跳过 |
| 服务器 | 两个 bot 在**同一服务器** | `onlinePlayers` 互见检查落空（只提示，不判失败） |
| 版本 | 已部署新 jar **且重启过客户端** | `@quota` 回 **404**（旧 jar 没这命令）；若 **403** 则是 `bridge.json` 白名单没含 |
| 端口 | 8761 / 8762 可达 | `curl http://127.0.0.1:8761/status` 先自测 |
| **物品** | **不需要** | 本轮新命令全是状态写入 / 行为控制：`@quota` 只写份额、`@forgetmem` 只改记忆、`@sleep wait-dawn` 不要床 |
| 唯一例外 | `@sleep bed-only` 会查"手里有床 **或** 24 格内有床方块" | 没有 → `failed`，**属正常结果**；脚本只报告不断言 |

**时段影响**：`@sleep` 的保持态断言**只在 `night` 有效** —— `WaitForDawnTask` / `SleepThroughNightTask` 的
`isFinished` 判据都是"白天 = 完成"，白天下发后一个 tick 就结束（这不是 bug）。白天跑时脚本会 **SKIP** 那条，
并改用 `@idle` 验同一套保持态机制。

**副作用提示**：
- `@resume_main` 会真的**启动主线**（bot 开始跑速通）；脚本会按测试前状态自动复位。
- `@forgetmem lava_lake` 会真删该类型的结构记忆（低价值）；只有 `--destructive` 才删全部。
- 脚本**刻意不测 `@sleep auto`** —— 它在没床时会派发找床任务（96 格远征），时长不可预估，不适合无人值守跑。

覆盖内容：

```
# 开两个客户端进同一服务器后（bridge 在 8761/8762），在本机跑：
python altoclef/bridge_smoke_test.py
# 或只测一台 / 只看用例 / 附带破坏性用例：
python altoclef/bridge_smoke_test.py --bots bot_1=http://127.0.0.1:8761
python altoclef/bridge_smoke_test.py --list
python altoclef/bridge_smoke_test.py --destructive
```

覆盖内容：
1. **12 条核心用例** —— `quota` / `forgetmem` / `sleep` 的成功与**失败 reason 断言**（含"少参数"这类解析错）；
2. `/status` 字段检查（`online` / `timeOfDay` / `onlinePlayers` / `sleepingPlayers` / `activeAction`）；
3. `@sleep` 后 `activeAction.cmd == "sleep"`；
4. **环境相关用例**（`bed-only`）只报告不断言 —— 有床→completed / 没床→failed 都算正常；
5. 第二实例（`bot_2`）独立链路 + 双方 `onlinePlayers` 互见；
6. **收尾自动复位**：测试前在跑主线 → `@resume_main`；否则 `@idle`。

⚠️ 两点注意：
- **限流**：默认 6 条/分钟（只读不占），全量约 2.5 分钟。想快就临时给三处 `bridge.json` 加 `"actionRatePerMinute": 200` 后重启，再 `--rate 200`（跑完改回 6）。
- `--destructive` 会执行 `@forgetmem all`，**清掉该 bot 本存档的全部自动结构记忆**（要塞/堡垒坐标等），非扔档测试别用。
