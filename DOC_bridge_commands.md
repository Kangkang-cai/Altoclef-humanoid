# 桥接命令对接手册（AI 行动通道）

> 面向 **chat_and_play（对端）**。2026-10-04 版。
> 代码 commit `0b79da92`；jar md5 `b67ae0ee2b831ec88f10ad519be361cc`。
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
| **按住·无终态** | `idle`（不带参数） | 受理后立即发 `action_done`（= "开始保持"），**不会再有第二条**；由下一条命令或 `@resume_main` 解除 |
| **按住·有终态** | `idle <秒>`（**2026-10-04 新**）、`sleep` | 受理后立即发 `action_done`（= "已开始保持/已开始睡"）；**到时/天亮时再发一条 `completed`**（idle 文案「待命 N 秒结束（原地站立），已自动回主线」）；`sleep` 被顶替 = `cancelled`；⚠️ **`idle`（两种形态）被顶替都静默不发**（与旧口径一致，靠 /status 感知） |
| **有任务** | `goto` `follow` `give` | 顶层任务**结束时**发 `action_done`（`status` = completed / failed / cancelled） |

> ⚠️ 对端**仍应设超时兜底**（建议 `goto` 60s / `give` 300s / 其余 120s）。有任务类在"被新命令顶替"时会立刻收到 `cancelled`；但若 bridge 与客户端断连就不会有事件。

---

## 2. 命令总表（白名单 14 条）

| 命令 | 语法 | 语义 | 类型 |
|---|---|---|---|
| `goto` | `@goto <x y z\|x z\|y\|维度>` | 导航到坐标/维度。**坐标属于别的维度时必须带维度参数**（`x y z 维度`，维度写 `overworld`/`nether`/`end`，大小写不敏感）；bot 当前维度看 /status 的 `dim`。不带维度 = 按 bot 当前维度解释坐标 | 有任务 |
| `follow` | `@follow <玩家名> [work]` | 跟随某人。可选第二参 `work`（**2026-10-06 新**）= 跟随 + 顺手干活：默认贴 3 格跟随，顺手时短暂偏离去搜刮就近容器 / 贪心挖可见矿，干完立刻回跟随（单次偏离 ≤30s、距目标 >32 格即放弃）。缺省 = 纯跟随，行为不变。目标与 bot **不在同一维度** → 首拍即 `failed`（不追过期坐标，2026-10-06 起，见 §4.3）；对端收到该 reason 可改派 `@goto <维度>` 或等目标换维度 | 有任务 |
| `give` | `@give <玩家名> <物品> <数量>` | 给某人物品（**背包没有会去收集**） | 有任务 |
| `idle` | `@idle [秒]` | 原地站立待命（可选时长，**2026-10-04 新**，见 §3.4） | 按住态（带秒=有终态） |
| `coords` | `@coords` | 报坐标（只读） | 瞬时 |
| `status` | `@status` | 报当前任务（只读） | 瞬时 |
| `inventory` | `@inventory [物品]` | 查背包（只读） | 瞬时 |
| `remember` | `@remember <名称> <x y z> [维度] [来源] [类型]` | 记一条**命名地标**（**类型参数 2026-10-05 新**，见 §3.5） | 瞬时 |
| `forget` | `@forget <名称>` | 删一条**命名地标** | 瞬时 |
| **`sleep`** | `@sleep [auto\|bed-only\|wait-dawn\|skip]` | **睡觉四态**（新） | 按住态（**`skip` 除外**，见 §3.1） |
| **`quota`** | `@quota <item_id> <count>` | **需求份额**（新） | 瞬时 |
| **`forgetmem`** | `@forgetmem <type\|all>` | **作废自动结构记忆**（新） | 瞬时 |
| **`share`** | `@share <on\|off> <type\|all>` | **记忆分享开关**（2026-10-05 新，见 §3.5） | 瞬时 |
| **`autorecord`** | `@autorecord <on\|off>` | **共享坐标自动落库开关**（2026-10-05 新，见 §3.5） | 瞬时 |

主线干预伪命令（不在上表，走同一入口）：`@pause_main` / `@resume_main`。

---

## 3. 本次新增命令详解

### 3.1 `@sleep [mode]` —— 睡觉四态

| mode | 语义 | 何时结束 |
|---|---|---|
| `auto`（缺省） | 有床用床，没床**就地找/造** | 天亮 / 新指令 |
| `bed-only` | **只用现成的床**：手里有床 或 24 格内有已知床方块才启动 | 天亮 / 新指令 |
| `wait-dawn` | **不睡**，站桩熬到天亮 | 天亮 / 新指令 |
| `skip` | **本夜不睡、也不找床**，立刻回主线干活 | **自动**：下一个白天 / 或收到任何显式 `@sleep` 指令 |

- 为什么：主线原版在没床时会去 96 格内找床 / 羊毛+木板造床 = **时长不可预估的远征**。`bed-only` 给你"要么就近睡，要么直说做不到"。
- `wait-dawn` 是**多人凑不齐全员入睡**时的正解（躺着等天亮 == 站着等天亮，但不会被钉在床边）。
- `skip` 的语义是**"当作今夜不该睡觉"**：不派任何睡眠任务，让主线的夜间睡眠路由让位，bot 照常挖矿 / 收集 / 推进主线 —— 也就是主线夜里"**跳过睡觉后启动的那条分支**"。
  - 与 `wait-dawn` 的区别：`wait-dawn` = 站着干等天亮；`skip` = 该干嘛干嘛，别浪费时间上床。
  - **只到下一个白天**（与主线"本夜放弃找床"同口径），不会让 bot 永久拒绝睡觉。
  - 想提前撤销：发 `@sleep auto` / `bed-only` / `wait-dawn` 任意一条（它们会顺手清掉 skip）。
  - ⚠️ **跳过睡觉 ≠ 天亮**：多人服里**只要有玩家醒着，夜晚就不会被跳过**。`skip` 的真实效果是"别上床，去干活，等真正的天亮（约 7 分钟真实时间）"。
- 起床不需要命令：任何新行动（`@idle` / `@goto` …）会顶掉它。
- 回执（**别只看第一条**）：
  - 受理即 `action_done(completed)` = "已开始睡 / 已开始熬 / 本夜已跳过"，`text` 是中文现场；`/status.activeAction` 显示 `cmd="sleep"`；
  - **`auto`/`bed-only`/`wait-dawn`：天亮自然结束** → 再发一条 `action_done(completed)`「已醒来（天亮），已自动回主线」（与 `goto`/`follow`/`give` 同款）；
  - **被新指令顶替** → `action_done(cancelled)`；**被 `@pause_main`** → `action_done(interrupted)`；
  - ⚠️ **`skip` 只有一条回执**（受理即完成）—— 它是"状态开关"，不是按住态：没有终态事件，也不写 `/status.activeAction`（bot 正在干活，不是待命）。别为它等第二条。
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

### 3.4 `@idle [秒]` —— 原地站立待命（2026-10-04 新增时长参数）

| 用法 | 语义 | 何时结束 |
|---|---|---|
| `@idle`（不带参数，**行为不变**） | 无限期待命 | 只有下一条命令 / `@resume_main` 解除 |
| `@idle 30` | **待命 30 秒，到时自动回主线** | 到时自动结束；或被新指令提前顶掉 |
| `@idle 0` / `@idle -5` / `@idle abc` | **当场拒绝，不起任务** | `action_done(failed)` + reason |

- **用途**：编排上的"定时等待"——给对方留拾取时间（give 前后）、错峰让路、按拍节推进等，不再需要靠对端计时器 + `@resume_main` 手动收尾。
- **`/status.activeAction` 期间明确报待命现场**：
  - 定时：`detail = "原地站立待命（不动），剩 Xs 自动回主线"`（**逐秒倒计时刷新**；`detail` 不进变化键，不会造成轮询刷屏）；
  - 无限期：`detail = "原地站立待命（不动），等你下一条指令"`。
- **回执（定时形态 = 两条，与 `@sleep auto` 同型）**：
  1. 受理即 `action_done(completed)`，text =「原地站立待命（不动）N 秒，到时自动回主线。」；
  2. **到时**再发一条 `action_done(completed)`「待命 N 秒结束（原地站立），已自动回主线」+ 通用 `task_done`「完成：Idle(Ns)」。bot 随即自主推进主线。
- **无限期形态不变**：只有第 1 条回执；被顶替**静默**（无第二条、无 cancelled）。
- ⚠️ **到时自动回主线是有意的**：否则任务结束后 TaskRunner 停摆（连怪物防御都不跑）。若你想让它待命后继续停着，到时再发 `@pause_main` 即可。
- **失败**：非正整数秒 → `failed`，reason 如「idle 参数必须是正整数秒（不传=一直待命），收到：0。」

### 3.5 被动式记忆分享（2026-10-05 新：`memory_shared` 事件 + `@share` / `@autorecord`）

**口径 = "默认做，AI 只否决"**：bot 自动入账的新结构记忆默认推送、收到的共享坐标默认落库；AI 角色不发任何命令就是全开。

**推送侧（bot 有了新记忆）**：bot 亲眼确认一个结构中心（KnowledgeMemory 入账）时，自动 emit 新事件：

```json
{"id":...,"bot_id":"bot_1","type":"memory_shared","strong":false,
 "text":"发现了岩浆湖 (123,64,-456,the_nether)",
 "detail":{"type":"LAVA_LAKE","pos":[123,64,-456],"dim":"the_nether"}}
```

- 只推**新入账**（同一结构合并半径内不重复推）；bridge 未启动不推。
- **编排层职责**：收到 A bot 的 `memory_shared` → 向 B bot `POST /command` 发
  `@remember <名称> <x y z> <dim> <A的botId> <type>`（source 填分享者、**type 原样带上 detail.type**，小写即可）。
  B bot 就自动落库为 `source=shared:A的botId` 的命名地标。
- AI 角色不想让某类记忆外流：`@share off <type|all>`（如 `@share off lava_lake`）；恢复 `@share on <type|all>`。只影响之后的新入账，不回收已推送的。

**记录侧（bot 收到共享坐标）**：`@remember` 新增第 6 个可选参数 `类型`（`lava_lake / nether_fortress / village / ruined_portal / blaze_spawner / warped_forest / stronghold / end_portal`，大小写不敏感）。**带类型的条目会被任务当结构线索自动消费**（找岩浆湖/找堡垒/召回刷怪笼/找诡异森林都会导航过去），不带类型的条目仍是纯地标（只能 `@goto` 直达）。

- AI 不想自动记共享来的坐标：`@autorecord off` —— 之后 shared 来源的 `@remember` 会被拒（`landmark_failed` 回执）；`@autorecord on` 恢复。self 来源（AI 主动记的）不受影响。
- 事后否决：`@forget <名称>` 删单条。
- **防伪造边界不变**：共享坐标只进命名地标（NamedLandmarks），**不进**自动结构记忆（KnowledgeMemory）——bot 导航到现场亲眼确认后，由既有感知管道自动晋升，不需要（也不允许）直接灌入。
- `@share` / `@autorecord` 都是瞬时状态写入，回执走 `action_done`，`text` 回显当前策略（如「分享=全开，但已关闭: LAVA_LAKE」）。

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
| `idle` | 秒参数非正整数（2026-10-04 新） | "idle 参数必须是正整数秒（不传=一直待命），收到：0。" |
| `sleep` | `bed-only` 且附近无床 | "bed-only 做不到：手里没有床、附近 24 格也没有现成的床；按约定不去远行找/造。" |
| `sleep` | mode 拼错 | "未知模式 'xxx'；可用 auto \| bed-only \| wait-dawn \| skip。" |
| `quota` | 物品 id 认不出 | "认不出物品 id —— xxx（示例：blaze_rod / ender_pearl / ender_eye）" |
| `quota` | 少参数 | "Not enough arguments supplied: You supplied 1. Usage: quota [item] [count]" |
| `forgetmem` | 类型认不出 | "认不出类型 —— xxx（可选：village\|…\|lava_lake / all）" |
| `forgetmem` | 少参数 | "缺 type 参数。用法 @forgetmem \<type\|all\>" |
| `give` | **背包没有该物品、也没法收集** | "做不到：背包里没有 xxx，也没有能收集它的方法（物品名可能拼错）。" |
| `give` / `follow` | 没给玩家名且无 butler 用户 | "做不到：没有指定玩家名，而当前没有 butler 用户可代指。……" |
| `follow` | 目标最后现身维度 ≠ bot 当前维度（**2026-10-06 新**；任务首拍即失败，不去走废路） | "目标最后现身于下界，与 bot 当前维度不同，无法跟随" |
| 任意 | 参数解析异常 | 解析器原文 + `Usage: <命令语法>` |
| 任意 | 派发期异常 | "命令派发异常" |

> **这条最要紧**：`@give` 在"物品不在背包"时**不会起任务**，旧版会让 `action_done` **永远不来**、你只能等超时。
> 现在它会立刻回 `failed` + 原因。**请把这段当"命令结果"消费，而不是只看 HTTP 200。**

### 4.4 `@remember` / `@forget` 的失败不看 `action_done`

这两条有自己的专用事件（`landmark_saved` / `landmark_failed`，`detail` 带 `{name,pos,dim,status,reason}`）。
`action_done` 只表示"命令受理了"。**判断成败请用它们自己的事件。**

(2026-10-05) `@remember` 会 `landmark_failed` 的新原因：
- `认不出结构类型 —— <token>`：类型参数写错（可选值见 §3.5）；
- `自动记录共享坐标已关闭（@autorecord off）...`：来源是 shared 且该 bot 关了自动记录 —— 编排层转发前可先 `@autorecord` 确认状态，或收到 failed 就跳过不重试。

---

## 5. `/status` 感知字段

| 字段 | 类型 | 语义 |
|---|---|---|
| `onlinePlayers` | string | 服务器在线玩家名（tab），逗号分隔；空则整键省略 |
| `sleepingPlayers` | string | **可见范围内**正在睡的玩家名，逗号分隔；无人睡则整键省略 |
| `activeAction` | object | `{actionId, cmd, phase, detail, sinceSec}`；无活跃行动则省略 |
| `mainlineProgress` | string | 如 `下界·烈焰棒2/8`；主线没跑则省略 |
| `timeOfDay` | string | `day` / `night` |
| `landmarks` | string | 命名地标 + 自动中心的 JSON 片段 |
| `role` | string | 本实例角色：`main`（主 bot）/ `sub`（次 bot）。**恒存在，不省略**。见 §5.1 |

> ⚠️ `sleepingPlayers` 只统计**已加载实体**的玩家（第三方服默认**48 格**追踪范围外拿不到姿态）。
> 它是"可见范围内谁在睡"，**不是服务器全量睡况**。判断"是否全员入睡"要意识到这个盲区。

### 5.1 `role` —— 主 / 次 bot 角色（2026-10-06 新）

**这是对端区分主次 bot 的权威字段**，不要再靠 `botId` 命名或端口号去推断。

| 值 | 含义 | 对端应如何对待 |
|---|---|---|
| `main` | 主 bot | 队伍核心：探路、找结构、丢眼定位；目标量按满档 |
| `sub` | 次 bot | 跟随位：跟着主 bot 走、顺手收集、缺料向主 bot 要；**模组侧已自动把木头/食物囤积目标降到 1/3** |

配置位置：每个实例自己的 `altoclef/configs/bridge.json`

```json
{
  "port": 8762,
  "botId": "bot_b",
  "role": "sub"
}
```

缺省 / 未知值 = `main`（保守：不会意外降档）。改动后需重连客户端生效。

**`role=sub` 时模组侧自动生效的部分**（对端不用重复下发）：

| 项目 | 主 bot | 次 bot |
|---|---|---|
| 木头囤积目标 `minWoodLogs` | 25 | 8 |
| 木头补货触发线 `woodRestockThreshold` | 6 | 2 |
| 食物囤积峰值 `foodPeakUnits` | 120 | 40 |
| 进下界前食物硬闸 `preNetherFoodUnits` | 150 | 50 |

**刻意不降的**：断粮线 `minFoodUnits`（那是"别饿死"的地板，与角色无关，降它只会让次 bot 更容易饿死）。

**模组侧不会自动做的事**（要由对端编排）：

1. 次 bot **不会**自动跟在主 bot 后面 —— 仍要派 `@follow <主bot名> work`；
2. 次 bot **不会**自动向主 bot 要料 —— 缺料时对端自行发 `@give <次bot名> <物品> <数量>`；
3. **木头 / 食物这两类"要跑远"的任务，模组侧没有做成随行顺手动作**。理由：它们的现成实现（囤木会自己找树、还可能先造石斧；`CollectFoodTask` 有远征兜底和熔炼）必然超出"贴 3 格跟随"的 30 秒 / 32 格边界，硬塞进去会被反复砍断。需要它们时由对端显式派活。

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
5. 凑不齐就别耗着：给等不下去的那个发 @sleep skip，让它去干活、等真实天亮
```

**为什么需要 `@sleep skip`**：只要有玩家醒着，夜晚就不会被跳过 ⇒ 全员入睡这件事在
"某个 bot 拿不到床 / 老是睡不进去"时会**卡住所有人**。`skip` 让协调者能明确选择
"这一夜不睡了，各自干活"，而不是让 bot 反复上下床或站桩空等。

**记忆记错了**
```
@forgetmem stronghold      # 作废本存档要塞记忆，让 bot 重新定位
```

**被动式记忆自动共享（2026-10-05 起，编排层职责）**
```
1. 轮询各 bot /events：type == "memory_shared"
2. 对其他每个在线 bot POST /command:
   @remember <名称> <x y z> <dim> <分享者botId> <detail.type 小写>
3. B bot 自动落库为 shared 地标；若任务在找这类结构会自动导航过去
4. 某 bot 不想收：@autorecord off；某 bot 不想发：@share off <type|all>
```

**给对方留拾取时间（give 前后的定时等待，2026-10-04 起）**
```
bot_1: @give bot_2 bread 5
bot_1: @idle 20             # 原地站 20 秒等对方捡，到时自动回主线，无需再发 @resume_main
```
待命期间 `/status.activeAction.detail` 会逐秒报「原地站立待命（不动），剩 Xs 自动回主线」，协调者可据此对齐双方节奏。

**主 / 次 bot 编队（2026-10-06 起）**

前提：各实例 `bridge.json` 里把 `role` 配好（见 §5.1）。模组侧会自动把 `sub` 的木头/食物目标降到 1/3，其余全靠编排。

```
1. 轮询各 bot GET /status → 读 role，确定谁是 main（不要靠 botId / 端口去猜）
2. 让 sub 跟上 main —— 这也是"顺手收集"的唯一入口：
   bot_2: @follow bot_1 work
   bot_3: @follow bot_1 work
3. 期间 sub 贴 3 格跟随；顺手时（最多每 30s 一次）短暂偏离去开就近箱子 / 挖可见矿，
   干完自动回跟随。用 /status.taskDetail 看它此刻在跟随还是在顺手干活。
4. 缺料由 main 主动补（sub 不会自己讨）：
   bot_1: @give bot_2 stone_pickaxe 1
5. ⚠️ 同一时刻至多一条 follow 边。A 跟 B、B 跟 A 会互追永不结束
   （follow 无内置超时），对端必须去重。
```

> 木头 / 食物这两类"要跑远"的任务，模组**不会**当成顺手动作做（理由见 §5.1 第 3 条）：
> `sub` 一旦走远或超时就会自动放弃回到跟随。要专门收这两样，由对端显式派活
> （`@goto` 到资源点，或让 `sub` 暂时脱离跟随去跑）。

---

## 7. 部署状态

commit `98a22506`（role / 次 bot 目标降档）+ `b946a6da`（随行宿主 `@follow <名> work`）；jar md5 `5653832ddb899729fedf07c14380027e`（4,528,759 B）；✅ **已部署** —— 2026-10-06 19:30 客户端全退（8761/8762/8763 均 free）后部署**四处**（`versions/1.18.2-Fabric-1/2/3` + 根 `.minecraft/mods`），旧 jar 备份为 `altoclef-0.4.0.jar.bak_20261006_1930`，四处 md5 与构建产物逐一一致。

本轮 jar 相对上一版（1006 12:06 `b193e0b3`，已部署四处）新增：**`role` 字段 + `/status.role`**（`98a22506`，见 §5.1）、**次 bot 木头/食物目标降 1/3**（`98a22506`）、**`@follow <名> work` 随行宿主**（`b946a6da`，见 §2 / §6）。
白名单默认 **14 条**（2026-10-05 起：+`share`/`autorecord`；`skip` 是 `sleep` 的一个 mode、秒是 `idle` 的可选参数、类型是 `remember` 的可选参数、`work` 是 `follow` 的可选第二参，都不另占白名单）；三处 `bridge.json` 都没有 `allowedCommands` 字段 ⇒ 自动生效，**不需要改配置**。⚠️ 但 `role` 是**新增字段**，需要给次 bot 实例的 `bridge.json` **手工加上** `"role": "sub"`（见 §5.1），否则一律按 `main` 处理。

**待实跑判据（`role` / 目标降档，2026-10-06 新）**
1. 次 bot 的 `bridge.json` 写入 `"role": "sub"` 并重启 → `GET /status` 应出现 `"role":"sub"`；主 bot 不配或配 `"main"` → 返回 `"role":"main"`。未知值（如 `"SUB "` 带空格、`"Sub"`）应仍然判为 sub（大小写不敏感 + trim）。
2. 次 bot 主线跑到囤木阶段 → `altoclef_debugstate.log` 的 `Stockpiling wood (logs): X/8`（主 bot 是 `/25`）；补货提示里的 `latch armed below 2`（主 bot 是 `6`）。
3. 次 bot 食物峰值锁存 → `altoclef_diag.log` 的 `[FoodPeak] peak reached food=N/40`（主 bot `/120`）。
4. 主 bot（或未配 role 的实例）行为应**逐字不变**：仍是 `/25`、`/120`、`/150`。

**待实跑判据（`@follow <名> work`，2026-10-06 新）**
1. 发 `@follow <主bot名> work` → `/status.taskDetail` 显示「跟随+顺手干活」；次 bot 应贴住主 bot 约 3 格（不是 32 格那种松跟随）。
2. 顺手命中时 `altoclef_diag.log` / 聊天出现 `[FOLLOW-WORK] 顺手接下正事 → <任务>`；结束时出现 `[FOLLOW-WORK] 正事完成 → 回跟随 <名>` 或 `[FOLLOW-WORK] 放弃正事 (<原因>) → 回跟随 <名>`，原因取值为「单次超 30s / 偏离 N 格 / 目标离开加载范围」。
3. 偏离期间距主 bot **不应超过 32 格**，也不应连续偏离超过 30 秒。
4. 目标不在同维度 → 结束并报 `failed`（reason 带维度，见 §4.3），**不应**出现每 tick 刷屏的重复警告。
5. ⚠️ 同一时刻至多一条 follow 边（互追死锁，见 §6）。

**待实跑判据（`@sleep skip`）**
1. 夜里发 `@sleep skip` → 立刻回一条 `action_done(completed)`，文案含「本夜不睡」；随后 `/status.task` 应变成主线子任务（挖矿/收集…），**不应**再出现 `Sleeping through night` / 找床。
2. `@sleep skip` 后**不发**第二条 `action_done`（它是状态开关，没有终态事件）；`/status.activeAction` 键应**消失**（bot 在干活而不是待命）。
3. 天亮后（或发 `@sleep auto`）→ 下一个夜晚应恢复正常睡觉（skip 自动/显式解除）。
4. ⚠️ **未验证项**：若命令到达时 bot **已经躺在床上**，任务被顶掉后它**会不会自己起身**（用户判断"换任务就会自己下床"，但仓里没有任何"任务切换→下床"代码，`PlaceBedAndSetSpawnTask.onStop` / `SleepThroughNightTask.onStop` 都是空的）。若发现它赖在床上不动，补一行 `setScreen(null)` + `STOP_SLEEPING` 即可，方案已备。

**待实跑判据（`@idle [秒]`，2026-10-04 新）**
1. 发 `@idle 30` → 受理即一条 `action_done(completed)`（text 含「原地站立待命（不动）30 秒」）；`/status.activeAction` = `{cmd:"idle", detail:"原地站立待命（不动），剩 Xs 自动回主线"}` 且 X 逐秒递减。
2. 30 秒到 → **第二条** `action_done(completed)`「待命 30 秒结束（原地站立），已自动回主线」+ 一条通用 `task_done`；随后 `/status.task` = 主线任务，bot 自主推进。
3. 待命途中发 `@goto ...` 顶掉 → **静默**：无 cancelled、无第二条 done（旧口径不变），靠 /status 感知。
4. `@idle 0` / `@idle abc` → 一条 `action_done(failed)` + reason「idle 参数必须是正整数秒…」，bot 原任务不受影响。
5. 定时待命期间 `/status.botStatus` 应保持 `ok`（待命是合法静止，不报 `bot_stuck`），restart_guard.log 不出现 IdleTask 重启记录。

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
- `@sleep skip` 的用例会把"本夜跳过"置上：**夜里跑完测试**，该 bot 会一直到天亮才恢复尝试睡觉（白天自动解除；想立刻撤销发 `@sleep auto`）。
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
1. **13 条核心用例** —— `quota` / `forgetmem` / `sleep`（含 `skip`）的成功与**失败 reason 断言**（含"少参数"这类解析错）；
2. `/status` 字段检查（`online` / `timeOfDay` / `onlinePlayers` / `sleepingPlayers` / `activeAction`）；
3. `@sleep` 后 `activeAction.cmd == "sleep"`；
4. **环境相关用例**（`bed-only`）只报告不断言 —— 有床→completed / 没床→failed 都算正常；
5. 第二实例（`bot_2`）独立链路 + 双方 `onlinePlayers` 互见；
6. **收尾自动复位**：测试前在跑主线 → `@resume_main`；否则 `@idle`。

⚠️ 两点注意：
- **限流**：默认 6 条/分钟（只读不占），全量约 2.5 分钟。想快就临时给三处 `bridge.json` 加 `"actionRatePerMinute": 200` 后重启，再 `--rate 200`（跑完改回 6）。
- `--destructive` 会执行 `@forgetmem all`，**清掉该 bot 本存档的全部自动结构记忆**（要塞/堡垒坐标等），非扔档测试别用。

---

## 9. 随行 / 角色验证脚本（`bridge_follow_test.py`）—— **不需要对端**

多 bot 随行那一批改动（`role` 降档 / `@follow work` / 贴 3 格跟随 / 偏离上限）的判据
散在「几何距离」和「日志标记」里，靠人盯屏幕既不可复现也不可信。本脚本把它们变成可重复跑的断言。

**与 §8 的分工**：`bridge_smoke_test.py` 管「命令回执契约」；本脚本管「多 bot 的行为与几何」。
两者都直连 localhost bridge，**都不需要 chat_and_play 在线**。本脚本复用前者的 `Bot`（HTTP / 限流 / 事件游标），不改它。

```bash
python altoclef/bridge_follow_test.py \
  --main bot_1=http://127.0.0.1:8761 \
  --sub  bot_2=http://127.0.0.1:8762 \
  --leader <主bot的MC玩家名，如 qiaoqiao> \
  --gamedir-main "D:/minecraft/.minecraft/versions/1.18.2-Fabric-1" \
  --gamedir-sub  "D:/minecraft/.minecraft/versions/1.18.2-Fabric-2"

python altoclef/bridge_follow_test.py --list          # 只列用例
python altoclef/bridge_follow_test.py --skip-geometry # 只跑不需要两个 bot 的用例
```

> ⚠️ `--leader` 要填 **MC 玩家名**（`qiaoqiao`），不是 bridge 的 `botId`（`bot_1`）——
> `@follow` 按游戏内玩家名寻址，两者不是一回事。

| 用例 | 断言什么 | 靠什么观测 |
|---|---|---|
| C1 | `/status.role` 存在且运行值正确 | HTTP |
| C2 | `@follow <leader> work` 后距离**收敛**到 6 格内 | 两 bot 的 `/status.pos` |
| C3 | 稳态中位数 ∈ [1, 6]，且全程不超 32 格 | 同上，稳定窗口采样 |
| C4 | `[FOLLOW-WORK]` 的放弃原因只允许「单次超 30s / 偏离 N 格 / 目标离开加载范围」 | `diag.log` |
| C5 | 次 bot 的囤木/食物目标**若出现则必须已降档**（≤12 / ≤60） | `debugstate.log` / `diag.log` |
| C6 | 跟随一个不存在的目标 → `failed` 且带 `reason`（证明有出口，不无限空转） | `action_done` 事件 |

**设计要点（这三条是这类脚本最容易翻车的地方）**

1. **几何断言**：`/status.pos` 是双 bot 都能读到的硬数据，「贴 3 格」因此可以客观断言 ——
   先等收敛，再在稳定窗口里采样判中位数。不再依赖「看起来贴着」。
   （前置：脚本会先给主 bot 发 `@idle` 让它站住，否则主 bot 自己在跑主线，距离没有稳态可言。）
2. **日志断言用锚点**：开始前记录每个日志文件的字节偏移，只读**锚点之后**的新增内容。
   否则会拿上一局的历史日志当「通过」——这是假阳性最典型的来源。
3. **契约型断言的措辞是「若出现则必须满足」**：例如囤木目标只在日志里真出现
   `Stockpiling wood` 时才断言它降到 8 而不是 25，避免「这局还没走到该阶段」被误判成失败。

**已知边界**：C5 依赖该实例日志目录可读（`--gamedir-*`）；不给就 SKIP 而不是 FAIL。
C3 需要主 bot 保持静止 —— 若主 bot 在跑主线，先 `@idle` 再跑本脚本。

