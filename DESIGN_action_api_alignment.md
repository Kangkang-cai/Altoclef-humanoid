# 跨侧对齐清单：行动 API（模组侧 → chat_and_play 侧）

> 用途：一页交付，可直接转给对方负责人。模组侧现状 = 已实现并部署
> （最新 commit `ab10d05b`，jar md5 `e80479d51d142041d770020d5aac09c1`），对端可开始接。
> 设计全文见 `DESIGN_action_api.md`（含命令分档理由、窄接口清单、不做清单）。
> 1002 15:3x 追加 **§五（给 chat_and_play 的补充）**：新增端点 8763、变化键排除易变字段、语义误判清单、待确认 3 件事。

## 一、已就绪的接口（对端直接接）

**端点**：`POST /command`（既有）；`GET /events?cursor=` 新增两类事件。

```http
POST /command
{"cmd":"@follow 小影","actionId":"a-1"}     ← actionId 可选，建议对端生成便于本地关联

200 {"ok":true,"actionId":"a-1","accepted":true}         受理（异步执行，不等待完成）
400 缺 cmd / 无命令前缀
404 未知命令
403 {"ok":false,"error":"not allowed","reason":"command 'stop' is not in the AI action whitelist"}
429 {"ok":false,"error":"rate limited","retryAfterSec":23}
```

**AI 行动白名单（默认 7 条，`bridge.json` 的 `allowedCommands` 可改）**
- 行动：`goto` `follow` `give` `idle`
- 只读：`coords` `status` `inventory`（**不占限流额度**）
- 限流：`actionRatePerMinute` 默认 **6 条/分钟**（滑动窗口），可按你们 LLM 的实际产出频率提。
- 白名单外一律 403——`@stop` / `@punk` / `@gamer` / `@custom` 等**刻意永不开放**，LLM 不要尝试，收到 403 就是设计行为不是故障。

**回执事件（`/events` 新增 type，均带 `detail`）**
```json
{"type":"action_accepted","text":"[a-1] 已受理：@follow 小影","detail":{"actionId":"a-1","cmd":"follow"}}
{"type":"action_done","text":"[a-1] 完成：FollowPlayerTask","detail":{"actionId":"a-1","cmd":"follow","status":"completed"}}
```
`action_done.detail.status`（1002 新增，四态）：
- `completed` 正常做完；
- `cancelled` 被对端下一条新指令顶替（`reason` = 如「被 @idle 顶替」）；
- `interrupted` 被 `@pause_main` 主动停掉（`reason` = 「被 @pause_main 暂停」）；
  ⚠️ 被战斗/吃食/逃生/死亡恢复**短暂抢占**不算终态——那些是挂起，任务会续跑，只用 `activeAction` 说明（见下）。
- `failed` 模组侧**确定做不到**才发：`follow` 目标从未出现过、或追到旧位置时目标已不在加载范围；
  `goto` 长时间（≥120s）算不出路径。**判不准一律不发 failed**，只发 `activeAction.phase=blocked` + 说明。

**主线干预（伪命令，非 `@` 注册命令）**
- `{"cmd":"@pause_main"}`：停下手头任何用户任务（含主线）。`action_done` 文本「已暂停手头任务」/「手头没有任务，无需暂停」。
- `{"cmd":"@resume_main"}`：回主线（内部重建 BeatMinecraft2Task，长期记忆承接、**会话内短期进度丢失**）。

## 一·补、现场执行状态（1002 新增：解决"派下去的任务到底在不在推进"）

**`GET /status` 两个新键（覆盖式，供轮询读，不发事件）：**

```json
"activeAction": {
  "actionId": "a-1",              // 对端透传的 id，回填
  "cmd": "goto",                  // 当前在跑的行动
  "phase": "running",             // running=在推进；blocked=卡住/够不着
  "detail": "导航中，剩余40格",     // 中文一句话现场
  "sinceSec": 12                  // 该行动已跑了多少秒
},
"botStatus": "ok",                // ok | stuck | recovering
"stuckReason": "任务 CollectIronTask 卡住 45s 无进度"   // 仅 stuck/recovering 时出现
```
- **没有活跃行动时 `activeAction` 整个键省略**（`@resume_main` 后 / 停下后）；`botStatus`/`stuckReason` 仅在线时出现。
- `phase=blocked` 判据（模组内部进度计数器，比外部猜坐标准）：路径态 + 目标距离 + 玩家可见性 + 收集数量，
  **N 秒（默认 15s）不变** → blocked。`detail` 会写明原因，例如
  `goto`→「导航中，剩余40格」/「已到位，等待确认」/「受阻：算不出路径」；
  `follow`→「跟随中，目标可见距离12格」/「受阻：目标不在加载范围」/「受阻：目标不在线」；
  `give`→「走近中」/「已抛出，等待对方拾取」/「背包没有，正在收集铁锭3/64」。
  被内部链短暂抢占时 `detail` 前缀「被打断（战斗），稍后继续；…」——**这不是失败**。
- `botStatus`（身体是否打结，与你的指令无关）：位置+背包+任务态 30s 全不变 → `stuck`
  （此刻发一条 strong `bot_stuck`）；恢复后 10s 内为 `recovering`（发一条 `bot_recovered`）→ 回 `ok`。
  `@idle`/`@pause_main`/开 GUI/睡觉/无任务在跑都**不算** stuck。

**`/events` 新增两个离散事件（只发一次，不进变化键）：**
```json
{"type":"bot_stuck","text":"我在执行的任务卡住了，可能需要重新下指令（… 已 45s 无进展）","strong":true,
 "detail":{"actionId":"a-1","cmd":"goto"}}   // 1002 15:3x 新增：附带当时活跃行动
{"type":"bot_recovered","text":"已从卡住中恢复，继续原任务","strong":false}
```
- `bot_stuck.detail`：`actionId` + `cmd` = **卡住时正在跑的 AI 行动**（供对端回执"你那条 `@goto` 卡住了"）。
  **无活跃行动**（主线/内部任务卡住）时两字段为**空串** `""`，对端据此区分"是不是它刚下的指令卡的"。

## 二、必须按此实现的 5 条（对端动作）

1. **超时策略按命令类型区分**（否则会把“还在跑”误判成失败并重发，两条任务互相顶）：
   - `goto`：建议 120s；`give`：建议 300s（可能触发收集）；`@pause_main`/`@resume_main`/`coords`/`status`/`inventory`/`idle`：立即回 `action_done`。
   - **`follow` 只有两种结束**：① 追到旧位置但目标已不在加载范围 → 发 `failed`；② 被新指令顶掉 → 发 `cancelled`。
     目标"只是暂时不在加载范围但还在服务器里"时**不会结束**（bot 会站桩等），此时用 `activeAction.phase=blocked`
     观察即可。**不要给 follow 设"失败重发"型超时**（重发会叠成两条任务互相顶）；要结束跟随就发新命令或 `@pause_main`。
2. **`@idle` 语义要写进 prompt**：受理即回 `action_done`，但实际是“永久停手头一切、站立不动”，直到下一条指令。它是 A 档里最重的一条，别当“短暂停”。
3. **多 bot 编排约束（模组侧刻意不兜，必须对端保证）**：
   - 同一时刻至多一条 follow 边（A follow B + B follow A 会互追死锁，follow 无超时）。
   - `give` 前让接收方先 idle（抛掷落点散布，移动目标会漏捡；掉落物 5 分钟消失）。
   - 接收方感知“收到了东西”靠既有 `collected` 事件（如“捡到了 5 个铁锭”），不需要新事件类型。
4. **give 的 prompt 约束**：**只 give 自己背包里有的物品**。对方背包不可见（真实底线），无从判断“对方缺什么”；且背包没有时会触发自主收集任务（可能几十分钟远征）。
5. **默认值可调**：白名单集合、`actionRatePerMinute`（6）、`enablePauseResume`（true）都在 `bridge.json`，需要改就提，不需要你们改代码。

## 三、已定：干预结束自动回主线 = B'（1001 对端拍板，实现 `8453094a`）

- **`goto` / `follow` / `give` 完成 → 自动回主线**（重建 `BeatMinecraft2Task`），`action_done` 文本附「已自动回主线」。
  - `follow` 的“结束”包括两类：目标玩家消失（FollowPlayerTask 自己 `stop`）与被新命令顶掉/`@pause_main` 取消；前者会发 `action_done` 并自动回主线，后者不发（对端已用 10 分钟 TTL 兜底跟随边）。
- **`@idle` / `@pause_main` → 按住侧**：语义就是“立正等到下一条指令”，**不自动回主线**，只由显式 `@resume_main` 解除。
- 备选 A（全部显式 resume）与 B（全部自动）已否决：A 漏发即停摆，B 会让 `@pause_main` 失去“长期按住主线”的能力。

## 四、模组侧内部事项（对端不需要处理）

pause/resume 的实现方式（重建任务实例）、任务链优先级与竞态、`_interrupting` 窗口抑制伪 `task_done`、`idleCommand` 配置项。

## 五、给 chat_and_play 侧的补充说明（1002 15:3x，现场执行状态落地后）

### A. 请务必同步（阻塞级）
1. **多一个实例端点**：本次新增第三实例 **`bot_3` = `http://127.0.0.1:8763`**
   （另两个仍是 `bot_1`:8761、`bot_2`:8762）。请把 **`GAME_BOT_PORTS` 加上 `8763`**，
   否则 bot_3 的 bridge 会正常起、但你们的编排拿不到它。
2. **⚠️ 变化键必须排除易变字段**（最容易踩的一条）：`activeAction.detail` / `activeAction.sinceSec` /
   `botStatus` / `stuckReason` 随 5s 快照**每轮都在变**。**不要把 `/status` 整个 payload 或这几个键
   当作"有变化就唤起 LLM"的判据**——会每 5s 刷一次。建议变化键只取稳定量：`/events` 新事件（游标）、
   `mainlineProgress`、`taskDetail`、关键 `equipment`/`food` 等。
3. **能力探测**：`activeAction` / `botStatus` / `stuckReason` 只在**重启加载新 jar 后**出现。
   连上某个实例后建议先探一次 `/status` 是否含 `activeAction` 键：**缺 → 该实例仍是旧 jar**，
   走"只靠超时"降级，别当成错误。

### B. 语义要点（避免误判）
4. **`activeAction` 缺席 = 当前无活跃行动**（不是错误、也不是"任务已结束"的信号）。它只在
   `goto`/`follow`/`give` 运行期、以及 `idle` 的驻留期（`detail="待命中，等你下一条指令"`）出现。
   `@pause_main`、`@resume_main`、只读命令（`coords`/`status`/`inventory`）**之后都不会有** activeAction。
5. **`phase=blocked` ≠ 失败**：只是内部进度计数器（路径态/距离桶/可见性/收集数）**15s 没变**，任务仍在跑。
   **不要因 blocked 就重发命令**——重发会 `setTask` 顶掉在跑的任务，你们会先收到一条
   `action_done(status=cancelled, reason="被 @X 顶替")`。
6. **`action_done` 缺席是设计允许的**：我方对无法语义匹配的结束**宁可丢弃也不发错配信号** ⇒
   单条命令可能永远收不到 done，必须靠你们侧超时兜底（建议值见 §二.1）。反过来可信任：
   **收到的 done 若 actionId 命中，就一定是这条命令的真实终态**。
7. **`detail` 前缀「被打断（X），稍后继续；」= 挂起，不是终态**（X=战斗/吃食/逃生/死亡恢复）。
   任务会自动续跑；此时**不要**判 follow/goto 结束、也不要重发。
8. **`failed` 只在我方"确定做不到"时出现**，只有两种：`goto` ≥120s 算不出路径；`follow` 目标**从未**出现过（180s）。
   **特别提醒**：若 detail 为「受阻：目标不在加载范围（正赶往最后位置，距N格）」——这是**不会失败**的
   （目标曾出现过），bot 会一直往最后已知位置赶；要结束请发新命令或 `@pause_main`。
9. **`bot_stuck` 是离散告警（`strong=true`，只发一次）**，恢复用 `botStatus: recovering→ok` + `bot_recovered` 表达，
   不会重复发。建议收到后**先别急于重发命令**（可能是短暂身体打结、会自愈），先看 `stuckReason` 与
   `activeAction.detail` 再决定是否 `@pause_main`。
10. **驻留态（`idle`）被顶替是静默的**：`idle` 被新指令顶替**不发 done**（它不是有终点的行动）。
    别等 idle 的 cancelled。
11. 重申 B'：`goto`/`follow`/`give` 完成 → **自动回主线**，无需再发 `@resume_main`；
    只有 `idle` / `@pause_main` 需要显式 `@resume_main` 解除。

### C. 想请你们确认的 3 件事
1. **轮询频率**：你们打算多久拉一次 `/status`？`activeAction` 随 5s 快照刷新；若你们拉得比 5s 更密没有增量收益，
   若想要更细粒度我方可以把快照周期调短（如 2s）——请给目标值。
2. **follow 的 10min TTL 到点你们怎么处理**（发新命令 / `@pause_main`）？确认后我方可在 detail 里给出更贴合的措辞。
3. **`bot_stuck` 要不要附带"是哪条行动卡了"**（现 `stuckReason` 是任务级；可在 `bot_stuck.detail` 附 `actionId`/`cmd`）？

### D. 本轮修复回执（1002 15:4x，另附两条待你方澄清）
- **B2 探针措辞 —— 已修**：`probeGoto` 的"已到位"改为 **feet 所在 BlockPos 严格命中目标格** 才报；
  水平 ≤2 但格进不去 → 「目标格站不进去（雪层/遮挡？），正在重找路径」，并**把该状态的进度 key 固定**，
  让"长时间站不进"能累积到 `goto` 的 120s 阈值、发出 `action_done(failed)`（否则 bucket 每拍抖动会不断重置计时，永远发不出 failed）。
- **B3 `/equipment` 混入主手 —— 已修**：`equipment` 语义收窄为**纯护甲签名**（adris 侧 `InventoryDigest`），
  裸装返回空串。⚠️ 代价：主手工具不再出现在 `equipment`（也不再进 `summary` 的"装备"子句）；
  若你方仍需要"手持什么工具"，我方可**另开独立字段 `holding`**，请告知，不混回 `equipment`。
- **B1 `@goto` 到点不结束 —— 未改动，待你方补充现场**：模组侧自查三实例最新会话日志，
  含你方点名的 `16:14:38 → 目标(-599,71,245)`，全部 `finished=true`（1.1–1.5s），无未配对 START。
  你方指出的 `CustomBaritoneGoalTask` "≤2 格 holding 无出口" **代码缺陷确实存在**（我方已复核），
  但在改动这个**全 baritone goal 任务共用**的分支前，需要你方给出**一次实际卡住的原始捕获**
  （时间戳 + 实例 + `/status` 原文 + 当时 `/events`），以免修错地方又引出回归。
- **B4/B5 `mainlineProgress` 空 + `taskDetail=待命` —— 疑为误判**：日志实锤该轮 bot 处于 `@idle` 驻留态
  （`chain=User Tasks task=<<Idle>>`，`shouldIdle=false` ⇒ 该 idle 来自你方 `@idle` 指令而非模组自身）。
  此时 `mainlineProgress` 省略、`taskDetail="待命"` **符合设计**；误导源是 `task` 字段——它是**链级**标签，
  只要胜出链是 `User Tasks` 就恒为 "主线任务"，即使链里跑的是 `Idle`。是否需要让 `task` 区分
  "主线在跑" vs "User 链在跑 idle"，请拍。
