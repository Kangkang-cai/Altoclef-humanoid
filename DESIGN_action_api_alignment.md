# 跨侧对齐清单：行动 API（模组侧 → chat_and_play 侧）

> 用途：一页交付，可直接转给对方负责人。模组侧现状 = 已实现并部署
> （最新 commit `ab10d05b`，jar md5 `e80479d51d142041d770020d5aac09c1`），对端可开始接。
> 设计全文见 `DESIGN_action_api.md`（含命令分档理由、窄接口清单、不做清单）。

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
{"type":"bot_stuck","text":"我在执行的任务卡住了，可能需要重新下指令（… 已 45s 无进展）","strong":true}
{"type":"bot_recovered","text":"已从卡住中恢复，继续原任务","strong":false}
```

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
