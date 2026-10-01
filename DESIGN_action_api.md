# 设计报告：AI 角色干预模组任务进程的最小行动 API（阶段三·行动通道）

> 2026-10-01 设计，待确认后实现。上游需求：`REQUIREMENTS_multiplayer_chat_bridge.md` §0
> （"模组负责会玩，LLM 负责像人"；LLM 只发高层指令，像"朋友喊一嗓子"，不像"玩家操控角色"）。
> 现状基线：阶段二 bridge 层已闭环（`GET /status` / `GET /events` / `POST /command`），
> 对端 L0 事件注入 + L1 快照注入已上线，行动通道（对端解析 `【行动】@xxx` → POST `/command`）未做。

---

## 0. 结论摘要

1. **现有 22 条 `@` 命令里，直接对 LLM 开放的只有 7 条**（goto/follow/give/idle/coords/status/inventory），其中 3 条只读。`@stop` 会把主线杀停且无恢复手段、`@punk` 是杀人命令——均永不开放。
2. **"干预主线"现状不支持自动恢复**：`@gamer` 主线本身就挂在 UserTaskChain（单任务位）上，`@follow`/`@goto` 会直接顶掉主线，做完进 idle，不会续跑。最小口子 = bridge 侧新增 `ActionDispatcher`（主线令牌 + 插队恢复），**模组任务框架零改动或仅一处小改**，不给 LLM 暴露 TaskRunner 调度权。
3. **安全模型**：bridge.json 配置化白名单（默认 7 条）+ 滑动窗口限流（默认 6 条/分钟）+ 回执事件（`action_accepted`/`action_done` 同步+异步两级，失败靠对端超时兜底）。白名单外 403。
4. **多 bot 语义天然可用**：`@follow <玩家名>`/`@give <玩家名>` 按 MC 用户名寻址，bot 在服务器里就是普通玩家。给物=走近+抛掷+对方拾取，接收方经背包增量已有 `collected` 事件感知，无需新事件类型。

---

## 1. 命令盘点与适配性分析

数据源：`AltoClefCommands.java`（22 条注册命令）+ 各命令实现源码逐条核对。

### 1.1 关键机制前提（决定一切适配性结论）

- **UserTaskChain 是单任务位**（`chains/UserTaskChain.java:84-101`）：`runTask` 直接 `setTask` 替换当前任务。`@gamer` 的 `BeatMinecraft2Task` **本身就跑在 UserTaskChain 上**（`GamerCommand.java:13`）——"主线"与"用户临时任务"不是两条链，是同一条链的两个时刻。
- 因此：任何 `runUserTask` 类命令（follow/give/goto/food/get/…）都会**顶掉主线**；任务结束后 `onTaskFinish` → 空闲或跑 settings 配置的 idle command（`UserTaskChain.java:104-141`），**不会自动恢复被打断的主线**。
- `@stop` = `userTaskChain.cancel` → 若 settings `shouldRunIdleCommandWhenNotActive=false` 还会 `TaskRunner.disable()`（`StopCommand.java:11-16` + `UserTaskChain.java:115-120`）→ **全部自动化停止，且没有任何"恢复"命令**。LLM 手滑 `@stop` = bot 装死到有人类来救。
- `@give` 的真实语义（`GiveCommand.java:33-46` + `GiveItemToPlayerTask`）：先 `TaskCatalogue` 查收集任务——**物品不在背包会去挖/合成**；拿到后走近目标玩家，`ThrowCursorTask` 把物品**扔出去**让对方捡。所以 `@give 铁锭 64` 可能触发一次 20 分钟的收集远征。
- `@follow <username>` 参数为 null 时走 butler 当前用户（`FollowCommand.java:17-27`）——bridge 派发时**必须带用户名**，否则只报 log 空转。

### 1.2 分级结论

**A 档：直接开放（7 条）**

| 命令 | 语义 | 评估 |
|---|---|---|
| `@goto x y z` | 导航到坐标 | 粗粒度移动，与"走过来"语义吻合；验证过实跑 |
| `@follow <玩家名>` | 跟随某人 | "跟我来"的直译；可被任意新任务顶掉，可逆 |
| `@give <玩家名> <物品> <数量>` | 给东西 | 协作核心；风险=隐含收集远征（见 §4 缓解） |
| `@idle` | 站住 | "先别动，听我说"；最无害的干预 |
| `@coords` | 报坐标 | 只读 |
| `@status` | 当前任务状态 | 只读 |
| `@inventory [物品]` | 查背包/计数 | 只读（注意：是 bot 自己的背包，符合真实底线） |

**B 档：二期再议（4 条，本期默认拒绝）**

| 命令 | 不开放理由 | 重开条件 |
|---|---|---|
| `@get <物品>` | 隐含不受控收集远征（挖钻石=下山下矿几十分钟），LLM 无法预估时长 | 加"仅限背包已有+浅层收集"的收窄语义后 |
| `@food <数量>` | 同上，觅食链会被 Food 链（prio 55）另行接管，语义重叠 | 同上 |
| `@equip <护甲>` | 低风险但低价值（bot 自己会穿甲），徒增手滑面 | 有真实需求时 |
| `@locate_structure <结构>` | 触发结构搜索，成本与返回形式未评估 | 评估后 |

**C 档：永不开放（11 条）**

| 命令 | 理由 |
|---|---|
| `@stop` | 杀停全部自动化且**无恢复手段**（§1.1）；LLM 没有可靠手段重启，人类不在场=bot 装死一整晚 |
| `@punk <玩家>` | `KillPlayerTask` 杀玩家，PVP 恶意行为，与 §0 协作定位相反 |
| `@gamer` | 主线重开会话入口——恢复主线必须走窄接口（§2.3 令牌重建），不允许 LLM 直接重开（会重复播报、绕过干预记账） |
| `@custom` | 语义过宽（任意任务名），等于开放半个调度权 |
| `@test` | 开发用 |
| `@record on/off` | 数据集录制开关，污染数据 |
| `@nightvision` / `@gamma` | 客户端视觉参数（影响录制画面口径），非游戏行为 |
| `@reload_settings` | 运维操作 |
| `@anomaly` | 运维诊断 |
| `@help` / `@list` | 无害但无行动价值，省限流额度 |

---

## 2. "干预主线进程"口子设计

### 2.1 LLM 需要的三种主线级干预 → 现状判定

| 干预需求 | 现状 | 判定 |
|---|---|---|
| 暂停主线（听人说话/先帮队友） | 不支持：`@stop` 杀停不可恢复，其余命令顶掉主线不恢复 | **需要窄接口** |
| 恢复主线 | 不支持：只能重新 `@gamer`（被 C 档禁了） | **需要窄接口** |
| 降低主线激进程度 | 不支持：激进度散在 Settings 十几个阈值里 | **刻意不做**（§5） |
| "先做队友的事再回主线" | `@follow` 顶掉主线后不回来 | 由暂停/恢复组合覆盖：pause → follow → resume |

### 2.2 核心设计决策：恢复主线用"令牌重建"，不复用任务实例

被顶掉的 `Task` 实例已 stop；对它二次 `setTask` 会走 `onStart` 二次进入——这是本项目已知的雷区（记忆 detail/01：onStart 必须自行 reset 清零，多数任务没保证）。而**重建**有现成先例：bot 死亡恢复、`@gamer` 重开都走 `new BeatMinecraft2Task()`，进度由 `KnowledgeMemory`（knowledge.log，按 save+dimension 持久化堡垒/要塞/传送门坐标）承接，不会从零开始。

结论：主线令牌存"任务标识符"（字符串），恢复时重建任务实例。

### 2.3 窄接口清单（文件/函数级落点）

**新增 1：`bridge/ActionDispatcher.java`**（全部干预逻辑收敛在此，bridge 包内部，不暴露到命令系统）

```
职责：
- 维护主线令牌 _mainlineToken（enum { GAMER, NONE }，当前只有 @gamer 一条主线，刻意枚举而非字符串——不给任意任务名开口子）
- pauseMainline()：
    判定 UserTaskChain.getCurrentTask() 非 idle（判定源：TaskRunner.getCurrentTaskChain()
    =="User Tasks" 且 getCurrentTask() != null 且 !isRunningIdleTask()——
    三个方法均已存在：UserTaskChain.java:143-145、TaskRunner.java:187，
    SingleTaskChain.getCurrentTask 已被 TaskRunner.java:125 引用）
    → 记令牌 → 调 AltoClef.cancelUserTask()（AltoClef.java:1472-1474 已存在）
    → 若本来就没在跑主线：返回 false（幂等，不算失败）
- resumeMainline()：
    令牌==GAMER → mod.runUserTask(new BeatMinecraft2Task())（与 GamerCommand 同构）
    → 清令牌；令牌==NONE → 返回 false
- 主线自然结束时令牌自清：订阅 TaskFinishedEvent（顶层）→ 若当前无活跃用户任务 → 令牌=NONE
  （防"暂停后 LLM 忘了 resume，人类 @gamer 手动重开"造成的令牌悬挂）
```

**新增 2：bridge.json 三个字段**（`BridgeConfig`，ConfigHelper 范式照旧）

```json
{ "port": 8761, "botId": "bot_a",
  "allowedCommands": ["goto","follow","give","idle","coords","status","inventory"],
  "actionRatePerMinute": 6,
  "enablePauseResume": true }
```

**不改的任务框架**：TaskRunner / TaskChain / SingleTaskChain 一行不动。`runUserTask` 既有插队语义（setTask 替换）就是干预通道，`cancelUserTask` 就是暂停通道——缺的只是"记账 + 恢复"，全部收敛在 ActionDispatcher。符合"宁可新增 1~2 个窄接口，不给 TaskRunner 通用调度权"。

**派发动作的桥接**：暂停/恢复不是 `@` 命令，ActionDispatcher 在客户端线程直接调上述方法（与 /command 现有 `client.execute` 派发同路）。

---

## 3. 白名单 + 限流 + 回执事件契约

### 3.1 /command 校验链（HTTP 线程，顺序）

```
POST /command {"cmd":"@follow 小影", "actionId":"a-<对端生成，可选>"}
  1. 405  非 POST
  2. 400  body 缺失 / 缺 "cmd" 字段 / cmd 空        （现状保留）
  3. 400  无命令前缀                                （现状保留）
  4. 404  unknown command                           （现状保留）
  5. 403  {"ok":false,"error":"not allowed","reason":"command 'stop' is not in the AI action whitelist"}
  6. 429  {"ok":false,"error":"rate limited","retryAfterSec":23}   ← 滑动窗口 60s 内第 N+1 条
  7. 200  {"ok":true,"actionId":"<对端的或新生成>","accepted":true} ← 派发成功即回，不等待执行
```

- 白名单读 `BridgeConfig.allowedCommands`（reload 回调热更新，与 ButlerConfig 同范式）；暂停/恢复经 `cmd: "@pause_main"` / `"@resume_main"` 语法糖进同一入口，由 ActionDispatcher 分流（这两个"伪命令"不在 CommandExecutor 注册，仅 bridge 识别）。
- 限流只计**行动类**（白名单内）请求，滑动窗口 60s，默认 6 条/分钟。只读命令（coords/status/inventory）不占额度（口径：限"改变世界状态"的行动）。
- `actionId`：对端生成则透传；未带则 bridge 生成 `a-<millis>-<seq>`。响应与后续回执事件都用它关联。

### 3.2 回执事件（/events 新 type，闭环关键）

```json
// 派发受理（立即发，让 LLM 下一轮就知道"话递到了"）
{ "id":"<millis>", "bot_id":"bot_a", "ts":"...", "type":"action_accepted",
  "text":"[a-1] 已受理：跟随 小影", "strong":false,
  "detail":{ "actionId":"a-1", "cmd":"follow", "args":"小影" } }

// 任务完成（异步：UserTaskChain 顶层 TaskFinishedEvent 与 pendingActions 匹配）
{ "type":"action_done", "text":"[a-1] 完成：FollowPlayerTask", "detail":{"actionId":"a-1"} }

// 同步拒绝（白名单/限流不走事件——对端从 HTTP 状态码已知，进事件反而刷屏）
```

实现落点与已知局限（**写明，不藏**）：
- `BridgeEvent` 加一个可空 `detail` 字段（JSON 对象/字符串，/events 输出时 `detail`:... 或省略），对端按 type 解析。
- `ActionDispatcher` 维护 `pendingActions`（actionId→时间戳，容量 8、TTL 5min）；EventCollector.onTaskFinished（现成的顶层任务结束订阅）命中时间窗内条目 → pop + 发 `action_done`。**乐观关联**：只对得上"派发后 5 分钟内结束的下一个顶层任务"，不校验语义相等——对行动粒度（goto/follow/idle 都是单任务）足够准。
- **action_failed 本期不做模组侧判定**：`@give` 物品名打错只走 log 不抛异常，模组拿不到可靠失败信号。失败的感知交给对端：actionId 发出后 N 秒（对端配置，建议 goto=60s、give=300s）无 action_done → 视为失败，可重发或换法。模组侧不补"失败事件"避免假阴性刷屏。
- `action_timeout` 不由模组发（模组不知道对端等多久）。

---

## 4. 多 bot 交互语义

| 交互 | 判定 | 依据 |
|---|---|---|
| `@follow <bot名>` 可用性 | **天然可用** | FollowPlayerTask 按玩家名每 tick 找实体，bot 在服务器就是普通玩家 |
| `@give <bot名> <物品> <数>` 可用性 | **天然可用** | GiveItemToPlayerTask 走近 + ThrowCursorTask 抛掷；对方 bot 有自动拾取 |
| 给物接收方感知 | **已覆盖，无需新事件** | 抛出的物品被接收 bot 拾取 → 背包增量 → 白名单内物品已有 `collected` 事件（"捡到了 5 个铁锭"）。verb 是"捡到"而非"收到"——语义上有细微差别，但结合聊天上下文（发起方刚说过"给你铁"）对 LLM 足够。新增 `received` 类型属锦上添花，进"不做"清单 |
| **互相 follow 死锁** | **真实风险，需对端编排约束** | A follow B + B follow A → 两 bot 互追永不停（follow 无超时机制）。对端行动层必须保证"同一时刻至多一条 follow 边"（LLM 侧 prompt 约束 + 对端去重）；模组侧不加超时（克制即真实，follow 被下一个任务顶掉即解除） |
| **给物时接收方在动** | **轻微风险，对端编排约定** | 抛掷有落点散布，接收方高速移动会漏捡（掉落物 5 分钟 despawn）。对端约定：give 前接收方先 idle。模组侧不做握手 |
| 给物隐含收集远征 | 见 §1.1 | 对端 prompt 约束 LLM："只 give 对方 keyItems 里可见的物品"；`@give` 未注册物品名时现逻辑只查背包（GiveCommand.java:39-45 fallback），失败仅 log → 对端超时兜底覆盖 |

---

## 5. 明确"不做"清单

| 不做 | 理由 |
|---|---|
| TaskRunner 通用调度权（任意链暂停/优先级改写） | 一条 API 打穿全部安全边界；暂停/恢复收敛到主线令牌一种 |
| 主线激进程度调节（Settings 阈值热调） | 激进度散在十几个阈值，语义不可逆且互相耦合；"降低激进"的真实诉求（别乱跑）由 pause+follow 组合覆盖 |
| `@get`/`@food` 开放 | 不受控时长；LLM 无从预估"挖钻石"是 5 分钟还是 50 分钟 |
| action_failed 模组侧判定 | 失败信号不可靠（log-only），假阴性比无信号更有害；对端超时兜底 |
| `received` 收物事件类型 | `collected` 已覆盖感知，加类型是重复信息 |
| follow/give 加超时保险丝 | 违反"克制即真实"（§0 约束 4）；互追死锁靠对端编排约束，模组不加机制 |
| bridge 直接暴露但ler（ whisper 控制通道） | 双通道并存会让"谁在指挥"失去唯一性 |

---

## 6. 实现期首个验证点（进实现前需查证）

1. `SingleTaskChain.setTask` 替换旧任务时，旧任务路径是否触发 `onTaskFinish`/`TaskFinishedEvent`——影响 action_done 关联窗口与令牌自清的写法（读 chains/SingleTaskChain.java 后定稿）。
2. `CommandExecutor.execute(cmd)` 抛 CommandException 时的返回路径（影响 400/500 划分——现状 404 在 HTTP 线程预校验，执行期异常目前吃进 client.execute 的 catch）。
3. `@idle` 之后 settings `shouldRunIdleCommandWhenNotActive` 的当前值（决定干预任务结束后 bot 站桩还是跑闲置命令——影响对端预期）。
4. 部署前按流程：说明 → 要求重启客户端（服务端 keepInventory=true，如需测死亡掉落先改回 false）。

## 7. 对端（chat_and_play）需按契约接的清单

1. LLM 输出行动行解析 → `POST /api/game/command`（带 `actionId`）。
2. 按响应码分支：200 记 actionId 等回执 / 403 提示 LLM 该命令不可用（可进下一轮 prompt）/ 429 按 retryAfterSec 退避。
3. `/events` 新 type：`action_accepted`（同步确认）、`action_done`（任务级完成）；对每个 actionId 设超时（goto 60s / give 300s / 其余 120s）。
4. 行动编排约束：同一时刻至多一条 follow 边；give 前接收方 idle。
