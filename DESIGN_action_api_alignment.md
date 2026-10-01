# 跨侧对齐清单：行动 API（模组侧 → chat_and_play 侧）

> 用途：一页交付，可直接转给对方负责人。模组侧现状 = 已实现并部署（commit `aa67f89a`，
> jar md5 `f2b5d2f5581c103b9c69935991ed5ccb`），对端可开始接。
> 设计全文见 `DESIGN_action_api.md`（本文件 §8 为其摘录）。

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
{"type":"action_done","text":"[a-1] 完成：FollowPlayerTask","detail":{"actionId":"a-1","cmd":"follow"}}
```

**主线干预（伪命令，非 `@` 注册命令）**
- `{"cmd":"@pause_main"}`：停下手头任何用户任务（含主线）。`action_done` 文本「已暂停手头任务」/「手头没有任务，无需暂停」。
- `{"cmd":"@resume_main"}`：回主线（内部重建 BeatMinecraft2Task，长期记忆承接、**会话内短期进度丢失**）。

## 二、必须按此实现的 5 条（对端动作）

1. **超时策略按命令类型区分**（否则会把“还在跑”误判成失败并重发，两条任务互相顶）：
   - `goto`：建议 120s；`give`：建议 300s（可能触发收集）；`@pause_main`/`@resume_main`/`coords`/`status`/`inventory`/`idle`：立即回 `action_done`。
   - **`follow` 没有完成态**：它只在“目标玩家消失”或“被新任务顶掉”时结束。**不要给 follow 设失败超时**；要结束跟随就发新命令（它自动顶替）或 `@pause_main`。
2. **`@idle` 语义要写进 prompt**：受理即回 `action_done`，但实际是“永久停手头一切、站立不动”，直到下一条指令。它是 A 档里最重的一条，别当“短暂停”。
3. **多 bot 编排约束（模组侧刻意不兜，必须对端保证）**：
   - 同一时刻至多一条 follow 边（A follow B + B follow A 会互追死锁，follow 无超时）。
   - `give` 前让接收方先 idle（抛掷落点散布，移动目标会漏捡；掉落物 5 分钟消失）。
   - 接收方感知“收到了东西”靠既有 `collected` 事件（如“捡到了 5 个铁锭”），不需要新事件类型。
4. **give 的 prompt 约束**：**只 give 自己背包里有的物品**。对方背包不可见（真实底线），无从判断“对方缺什么”；且背包没有时会触发自主收集任务（可能几十分钟远征）。
5. **默认值可调**：白名单集合、`actionRatePerMinute`（6）、`enablePauseResume`（true）都在 `bridge.json`，需要改就提，不需要你们改代码。

## 三、唯一需要你们拍板的一条

**干预任务结束后，要不要自动回主线？**

- **A（当前行为）**：不自动。LLM 必须显式发 `@resume_main`。风险：漏发即永久停摆（模组侧 `idleCommand` 为空时任务结束会直接停掉全部自动化，连自保都不跑）。
- **B**：所有干预任务结束自动续跑主线。贴合“帮完队友回主线”，但 `@pause_main` 失去“长期按住主线”的能力。
- **B'（模组侧建议）**：只有 `goto`/`follow`/`give` 这类“有终点的行动”结束后自动回主线；`@pause_main` 仍保持人工按住，等显式 `@resume_main`。

请回一句选 A / B / B'。选定后模组侧再改实现（当前已部署的是 A）。

## 四、模组侧内部事项（对端不需要处理）

pause/resume 的实现方式（重建任务实例）、任务链优先级与竞态、`_interrupting` 窗口抑制伪 `task_done`、`idleCommand` 配置项。
