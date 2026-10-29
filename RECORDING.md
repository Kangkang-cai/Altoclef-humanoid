# 录制模块 (训练数据集导出) —— 长期记忆

> 位置: `src/main/java/adris/altoclef/record/` (8 个类, ~1600 行)
> 本文是**这个模块的长期记忆**。改录制模块前先读本文, 改完把新结论回写本文。
> 最后更新: 2026-09-17

---

## 1. 这个模块要解决什么

给**层次化 MBPN** 训练产出语料: 逐帧记录"**bot 看到了什么 → 它决定做什么 → 它执行了什么**"。

已拍板的边界 (不要再问):

| 决定 | 取值 | 原因 |
|---|---|---|
| 录制方式 | **逐帧全量快照** (不差值、不重放) | 事后可从任意一帧独立还原, 不需要状态机重放 |
| 决策频率 | **20Hz** (`Settings.taskTickInterval=1`) | "5Hz" 只是历史注释残留, 不做稀疏化假设 |
| 输入模态 | **结构化世界 + 结构化记忆, 不录像素画面** | 训练不需要渲染管线; 结构化数据量小一个数量级 |
| 任务层级 | **3 层** (L1 语义 / L2 操作带参 / L3 原语), 无独立仲裁层 | 数据里保留 `chain` 字段区分目标式/反应式 |
| 层级归属 | **录制端不做层级判定** | 只导原始任务栈, L1/L2/L3 词表离线收割; 手写词表会漏掉新加的任务类 |
| 表达空间 | token 向量 (预设初始化 + 可学习, 不冻结) | —— |
| 记忆 | **完整录快照 + 读写事件双轨** | 快照保证任意时刻可还原; 事件给"这个 tick 查了哪块记忆" |

---

## 2. 五分钟上手

```
@record on       # 开录 (下一 tick 建目录写第一帧; 顺带清掉"出错自禁用")
@record          # 看状态: 开关/会话目录/各通道已落盘行数/丢弃行数
@record off      # 停录 (下一 tick 收尾刷盘)
```

- 命令实现: `commands/RecordCommand.java`; 状态文本: `RecordManager.statusReport()`
- **只改内存字段, 不回写 `altoclef_settings.json`**。要永久开启改配置文件里的
  `recordDatasetEnabled`。
- 输出: `<游戏运行目录>/record/<SESSION_STAMP>[-rN]/`
  本机即 `D:\minecraft\.minecraft\record\20260917-100000\`

**绝对不要**放到 `logs/altoclef/` 下 —— `Debug.pruneOldSessionsOnce` (KEEP_SESSIONS=10)
会把最旧的会话目录整目录删掉, 训练语料会被清。

### 会话目录内容

| 文件 | 通道 | 写入节奏 |
|---|---|---|
| `frames.jsonl.gz` | A 时间 + B 任务标签 + C 自身状态 + G 动作 | 每 tick 一行 |
| `perception.jsonl.gz` | D 可见世界 (方块/实体/声音) | 每 tick 一行 |
| `memory.jsonl.gz` | E 记忆快照 (world/knowledge/valuable + blockcache 决策候选集) | **只在写版本号变化时** (blockcache 另有 ≥3s 间隔) |
| `events.jsonl.gz` | 记忆读写 / 受伤 / 死亡 / 任务切换 | 稀疏 |
| `meta.json` | 种子/维度/存档名/构建 ID/口径与分级表/设置快照/结束统计 | 开录 + 收尾各写一次 |

**会话 = 一次"世界内连续运行"**。离开世界 / 断线 / 换世界 → 自动收尾, 再进来开新目录
(`-rN`)。换**维度**不切会话 (每帧的 `dim` 字段区分)。

---

## 3. 挂载点与调用链 (改之前必须知道)

```
AltoClef.onClientTick()
  ├─ _trackerManager.tick()          ← tracker 刷新
  ├─ _taskRunner.tick()              ← 链仲裁 + 任务树 tick
  ├─ getControllerExtras().applyCombatAim()   ← 动作/准心最终定下来
  └─ RecordManager.tick(this)        ← ★ 唯一采样点
```

选这里的唯一理由: 全 tick 里**唯一**"tracker 已刷新 / 任务树已定型 / 动作已下发"
三态一致的时刻。

收尾 (`onSessionEnd`, 幂等) 挂在 **4 个**地方:

| 触发 | 位置 |
|---|---|
| `@record off` / 配置改回 false | `RecordManager.tick()` 检测开关变 false |
| 离开世界 / 断线 / 死亡菜单 | `RecordManager.tick()` 检测 `!AltoClef.inGame()` |
| 停 bot | `TaskRunner.disable()` → `onBotStopped()` (收尾 + 上闩, 见下) |
| 直接退出游戏 | JVM shutdown hook (`ensureShutdownHook`, 短超时 1200ms) |

代价: 关掉录制后 `tick()` 每帧仍会调一次 `onSessionEnd()`(内部 `_frames == null` 早退),
可以忽略。

### 会话边界的一条硬规则: 停 bot ≠ 离开世界 (2026-09-17)
停 bot (`TaskRunner.disable()`) 是收尾出口, 但它**不离开世界**。若收尾后下一 tick 又因
`_frames == null` 自动 `startSession`, "用户停下 bot" 就被切成了两段会话 —— 实测
20260917-155653: 16:12:14.233 收尾, **41ms 后**自动开了 `-r2`, 用户站着没动却被录了
13 分钟空任务 (16183 帧全是 `User Tasks` 无叶子)。

所以收尾与自动开录是解耦的: `TaskRunner.disable()` 走 `RecordManager.onBotStopped()`
(= 收尾 + 上 `_pausedAfterStop` 闩), 闩着时 `tick()` 直接返回。

解除闩的四条路 (2026-10-29 更新, 原为两条):

| 出口 | 触发 | 结果 |
|---|---|---|
| `@record on` (`clearDisabled`) | 人工 | 开新会话段 |
| 离开世界再进 (`!inGame()` 清闩) | 人工 | 开新会话段, **一个世界访问 = 一个会话** |
| `ActionDispatcher.resumeMainline()` 两个分支 | `@idle`/`@sleep` 到期、桥命令动作完成 | 解闩 → **切新 `-rN` 段** |
| `UserTaskChain.maybeStartSubBotBootstrap()` | 次 bot 自启 (每 tick 检查) | 解闩 → **切新 `-rN` 段** |

⚠ 关键语义: 后两条解闩**不是"续写同一会话"**。`onBotStopped()` 已经 `onSessionEnd()`
收尾, 解闩后下一 tick 必 `startSession()`、`_sessionIndex++`, `_seq` 从 0 重来。
所以"停一次 bot 再自动恢复"必然多一个 `-rN` 目录; 依赖「单目录 = 单次运行」的离线
收割脚本要按 `meta.json` 的会话序号聚合, 不能按目录数计运行次数。

---

## 4. 各通道的数据形态

### frames.jsonl.gz (A/B/C/G)

```json
{"seq":1,"t":306,"wt":1789610428997,"dim":"minecraft:overworld",
 "env":{"day":6000,"biome":"minecraft:plains","skyL":15,"blkL":0,"rain":false,"thunder":false},
 "labels":{"chain":"User Tasks","chainPrio":50.000,
           "chains":[{"name":"User Tasks","prio":50.000,"active":true}, ...],
           "stack":[{"i":0,"c":"CollectItemTask","d":"Collect 3 logs","s":"Collecting","f":false}, ...]},
 "self":{...},"act":{...}}
```

- `seq` = 录制器自增序号 (跨通道对齐用); `t` = 游戏 tick; `wt` = 墙钟 ms
- `env` (2026-09-17): 日时间/生物群系/天光/方块光/雨/雷 —— 刷怪预测与行为节奏的直接输入
- `labels.stack` 是**本 tick 完整任务栈** (父→子, 由 `TaskChain.addTaskToChain` 累积),
  最后一个元素就是叶子任务
- **`chainPrio` / `chains[].prio` 为 `null` 有两种含义**: ①该链本 tick 非活跃 (`chains`);
  ②仲裁根本没跑 (`chainPrio` = -inf 被 `Json.f` 写成 null)。分不清时看同帧 `chains[]`
  是否为空 —— 空 = TaskRunner 被早退 (`!_active` 或 `!inGame`)。
- `self` 字段: pos/vel/block/yaw/pitch/headYaw/fallDist/hp/maxHp/food/sat/air/xpLevel/
  onGround/inWater/submerged/inLava/onFire/sneaking/sprinting/swimming/riding/dead/
  main/off/hotbarSel/usingItem/cursor/effects[]/inv{}
- `act` 字段: keys[]/bkeys[]/pathing/customGoal/builder/pathLen/pathDest/
  breaking{pos,prog}/screen/syncId/**slots[]**/atkCd/swing
- `act.slots` (2026-09-17): **容器类界面打开时**导出 handler 全槽位 `{"i":下标,"id":物品,"n":数量}`,
  空槽跳过; `i` 就是 ClickSlot 协议下标。熔炉燃料/箱子内容/合成格材料 —— 容器侧之前完全没录,
  是训练熔炼/合成任务的直接输入。PlayerScreenHandler 不导 (背包已有 self.inv)。

### perception.jsonl.gz (D)

```json
{"seq":42,"t":1424,
 "blocks":[{"p":[376,-38,-645],"b":"minecraft:furnace","d":2.5,"src":"los","g":"observed","age":3,
            "st":{"facing":"north","lit":"true"}}],
 "entities":[{"id":686,"type":"minecraft:item","p":[..],"v":[..],"d":2.2,"hp":null,
              "item":"minecraft:gravel","n":1,"src":"sight","g":"observed"}],
 "sounds":[{"id":"entity.zombie.ambient","p":[..],"cat":"hostile","v":1.0,"pt":1.0,"ageMs":600,"d":9.4}],
 "stats":{"candBlocks":..,"shownBlocks":..,"sightCache":..,"slice":..,"slices":25,
          "nearR":12,"farStep":4,"maxBlocks":512,"maxEntities":64,...}}
```

- `blocks[].st` (2026-09-17): 方块状态属性 —— 只给 **d ≤ `recordPerceptionStateRadius`(默认 5) 且
  本扫描圈确认过 (fresh)** 的方块; 非 fresh 永不带 st (**诚实边界: 不给老记忆造假新状态**)。
  无属性方块 (石头等) 不输出。近距操作 (开门/取燃料/收作物) 的直接输入。
- `sounds[]` (2026-09-17 重做): 源改为 `SoundSystemMixin` 在**声音引擎总入口**截获的录制专用
  缓冲 (8s 窗口 + 128 上限) —— 服务端广播/实体语音/自己动作音/环境音全覆盖。旧源只收
  `PlaySoundS2CPacket`, 实测整段 0 条。可闻过滤 `d ≤ 16×volume` (游戏衰减机制近似)。
  SoundTracker (bot 决策输入) 语义零改动。

### memory.jsonl.gz (E)

```json
{"seq":1,"t":306,"rev":{"world":0,"knowledge":1,"valuable":0,"blockcache":42},
 "world":{"ore:minecraft:iron_ore":[[x,y,z],...],"animal:minecraft:sheep":[...],
          "portal":[...],"portal_lost":[...],"bed":[...],"grave":[...]},
 "knowledge":{"center:FORTRESS":[...]},
 "valuable":{"valuable:minecraft:lava":[...]},
 "blockcache":{"dim":"NETHER","types":{"minecraft:warped_nylium":[[x,y,z],...],
          "minecraft:crimson_nylium":[...]}}
```

`blockcache` (2026-09-21 录制同步): **BlockTracker 缓存 = bot 决策候选集的准确口径**。
巡逻目标 / 失联远走目标 / 收集材料取最近 / "看见森林"判据全从这里出, 而感知通道
只录视线口径 —— 没有它离线无法解释"为什么朝林子/那块矿走"(含玩家没直视过的块)。
门控: `rev.blockcache` 变化 **且** 距上次写 ≥3s 才写 (缓存被近区 5Hz 扫描持续喂入,
不设间隔会刷屏); 未到期时该字段为 `null` 占位。天然有界: 单类型 ≤
`maxCacheSizePerBlockType=100`, 类型数 = 当前被任务注册的类型。`dim` 用
`WorldHelper.Dimension` 枚举名 (OVERWORLD/NETHER/END), 精确维度看 frames 的 `dim`。

### events.jsonl.gz

```json
{"t":415,"wt":...,"seq":110,"type":"leaf_switch","data":{"chain":"User Tasks","from":"...","to":"..."}}
```

类型: `memory_write` / `memory_read` / `damage` / `heal` / `death` / `respawn` /
`chain_switch` / `leaf_switch` / `blacklist` / `anomaly` /
`fireball_evade` / `fireball_repath` / `breath_touch` / `breath_clear` / `breath_near` / `breath_near_clear` /
`diag` / `diag_dropped`

- `blacklist` (2026-09-17 决策知识入流): `AbstractObjectBlacklist.blackListItem` 是方块
  (`WorldLocateBlacklist`) + 实体 (`EntityLocateBlacklist`) 黑名单的**唯一写入汇聚点**。
  data: kind/pos/fail/allowed/total/tool/reset/banned/first。**`banned:true` = 该目标已从
  搜索中排除** —— bot 放弃/绕路/换目标的官方归因, 训练时不至于瞎猜。
- `anomaly` (2026-09-17 决策知识入流): `AnomalyWatchdog.emit` (轮询+事件式上报的唯一汇聚点,
  自带 (type,key) 冷却去重)。data: type/key/sev/sum —— stall/oscillation/unreachable/idle/
  duration 看门狗结论。
- `fireball_evade` / `fireball_repath` (2026-09-21 末地龙息链路决策知识入流):
  `DragonBreathTracker.FireballEvadeTask` 躲避启动 (首次威胁评估为真) 与撞击点前移重寻路。
  data: impact/bot (撞击点与 bot 当时方块坐标)。火球的逐帧 p/v 在 perception 通道
  (实体级, 含速度), 逐帧决策标签在 frames 通道 (`s: EVADE Dragon Fireball (pillar up)
  impact=...`, toDebugString 已带参) —— 本事件只把"哪次威胁触发了哪个决策"链接起来。
- `breath_touch` / `breath_clear` / `breath_near` / `breath_near_clear` (2026-09-21 同上):
  `BeatMinecraft2Task` 末地守卫的边沿事件 —— 进/出云伤害区 (实体级判定, 对齐原版
  AreaEffectCloudEntity.tick 语义) 与进/出"云贴脸 10 格"主动规避圈。data: pos。
  云的逐帧 p/r 在 perception 通道 (AreaEffectCloudEntity 带 radius) —— 离线可复原
  危险区随时间的生长 (云半径 0→5 动态增长)。
- `diag` / `diag_dropped` (2026-09-21 诊断日志单点入流): `Debug.logToFile` 是全项目
  诊断 (altoclef_diag.log / altoclef_chat.log 聊天镜像 / altoclef_trace.log) 的**唯一
  汇聚点**, 在落盘的同时喂进事件流 —— 一处收全部。data: file/msg 原文。
  覆盖的决策依据: `[JumpStuck]` 强制干预全套 (order/forceCancel/wander 拽离/豁免)、
  `[DigGuard]`/`[LavaGuard]`/`[LavaEscape]`、`[VOID-GUARD]` 虚空列拒绝、巡逻缓存候选
  与锚点漂移、ChunkSearch 零行为诊断、末影人失联引出 (`[EndermanLure]` 族)、
  `[Portal-*]` 门链路。**节流: 每文件 250ms 一条 + 全局 25 条每秒**, 超限丢弃只计数,
  `diag_dropped` 5s 至多汇报一次累计条数 —— 稀疏决策日志远低于阈值不会被节,
  刷屏型日志 ([Liquid-avoid] 类) 恰好被压掉。异常静默吞掉, 绝不反噬行为。

---

## 5. 设计规则 (照做, 别自己发明)

### 5.1 任务标签只导原始栈, 不做层级判定
`c`=类简单名 / `d`=`Task.debugString()` / `s`=`Task.debugState()` / `f`=`isFinished`。
L1 标签源优先取 `s`; **`s` 为空时离线回退取 `d`**(约 8% 的帧叶子任务不调 `setDebugState`,
实测 202/2394)。

### 5.2 感知必须分级, 且分级只有一处实现
`VisibilityHelper.perceiveSource(pos)` 是**唯一**的可见性判定入口 —— 它和
`isBlockPerceivable` 共用 `perceiveSourceInner`, 所以**绝不会出现"判定改了、分类没改"的漂移**。
录制端不许自己写第二套判定。

| 粗级 | 细来源 (`src`) |
|---|---|
| `observed` | `los` / `fluid_los` / `fluid_surface` / `portal` / `bed` / `sight` / `sound` |
| `inferred` | `structure_inference` / `canopy_inference` / `stone_medium` / `experience` / `crafting_table_near` |
| `remembered` | 方块: 本圈未再确认但仍在 `recordSightMemorySeconds` 窗口内<br>实体: EntityTracker 的 20s 感知记忆窗口内 |

### 5.3 感知通道 = 项目自己的视觉系统, 不是"任务 track 的类型"
这是**踩过坑的设计**: 最初候选集用 `BlockTracker.getTrackedBlockTypes()`, 结果
守炉/旅行/战斗时该集合≈空 (实测 2495 帧总共只录到 2377 个方块, 全是那台熔炉自己),
"世界"一个字节没录, 对 L3 移动原语训练完全无用。

现在的实现 (`PerceptionSampler`):

| 环节 | 做法 | 与项目的关系 |
|---|---|---|
| 看得见/看不见 | `VisibilityHelper.perceiveSource` | 与 bot 决策**同一套规则** |
| 近场扫 | 半径 `recordNearScanRadius`(12) 内 step=1 逐格 | 与 `BlockTracker` 近区 (5Hz r16 step1) 同构 |
| 远处扫 | 按 `recordFarScanStep`(4) 稀疏, 半径 = `recordPerceptionRange`(48) | 与 `BlockTracker` 远区 (r128 step2) 同构 |
| 瞬时记忆 | **录制器自持** `pos → 最后可见 tick` | `BlockTracker` 无共享实现; 项目的 5s/40s 记忆是 `MineAndCollectTask` **私有**的 |

- 与 `BlockTracker` 的**唯一区别**: 录制器扫**全类型**, 不限于 `trackBlock` 过的。
- 视觉记忆是**录制专用**的 —— 关掉录制就不存在, 完全不碰任务状态。
- 只有过了 `perceiveSource` 的方块才进记忆 → **反透视边界不变** (被岩石包住/隔墙的
  永远不出现)。

### 5.4 扫描必须分期 (性能)
近场 12 格 = 25³ 格, 逐 tick 全扫不可能。按 X 列分期: **每 tick 只扫 1 列**
(近场 25×25 + 远场 13×25 ≈ 950 次 `getBlockState`), `slices = 2*nearR+1 = 25` tick
(1.25s) 走完一圈。每格先判空气 (绝大多数是空气, 一次查询就挡掉), 再走 `perceiveSource`
(其内部 LOS 复用 `_losCache` 400ms 缓存)。

### 5.5 动作是 API 级聚合, 不插桩调用点
在 tick 末尾一次性读各子系统状态。可用 API: `InputControls.isHeldDown`、
baritone 的 `isPathing/getPath/getCustomGoalProcess/getBuilderProcess`、
`PlayerExtraController.getBreakingBlock*`、`player.currentScreenHandler`。

**两套按键必须分开记**:
- `keys` = AltoClef 自己 `InputControls` 按下的键
- `bkeys` = baritone `InputOverrideHandler` 强制按下的键

baritone 走 override **不 setPressed** —— 只读 `isHeldDown` 看不到任何真实移动
(此坑见 `NavStuckWatchdog` 注释)。

### 5.6 记忆只在变化时写
三个记忆子系统各维护"写版本号", 录制器每 tick 只比版本号:
`WorldMemory.getMemoryRevision()` / `KnowledgeMemory.getMemoryRevision()` /
`VisibilityHelper.getValuableMemoryRevision()`。

版本号**只在真的新增时才 +1** —— 曾经 `KnowledgeMemory.ensureCentersLoaded` 无条件 +1,
写出过"rev 变了但内容还是空"的假快照 (实测第 2 行 `rev.knowledge=1` 而 `knowledge={}`)。

### 5.7 事件: 记什么、不记什么
- 记: `memory_write`(写入点插桩) / `memory_read`(**只在决策入口** `getNearest*`、
  `getRememberedCenters`, 内层循环的 `getXxxLocations` 不记以免淹掉事件流, 且同 tick
  按 (类型,键) 去重) / `damage` `heal` `death` `respawn` `chain_switch` `leaf_switch`
  (从逐帧状态派生) / `blacklist` `anomaly` (2026-09-17 决策知识入流, 见 §4)
- **故意不记**: 合成/物品变化事件 —— 背包逐帧全量已含该信息, 重复导出只是翻倍体积。
  ⚠️ 这条前提依赖 `recordInventoryPerTick=true`; 若关掉它, 合成事件就**推不出来**了。

### 5.8 读写纪律 (硬性)
- **严格只读**: 不调用任何有副作用的 API。尤其**绝不重复调 `TaskChain.getPriority()`** ——
  `WorldSurvivalChain.getPriority` 会 `setTask`, 重复调会搞乱 bot 行为。各链优先级由
  `TaskRunner` 在仲裁循环里**顺带缓存**(`getCachedPriorities()` / `getCachedWinnerPriority()`),
  录制器只读这份快照。
- **绝不影响行为**: 全包 try/catch; 出错只计数, 连续 20 次直接自禁用 (不重试刷屏)。
- **零开销**: 开关关闭时只有一次布尔判断 + null 检查。
- **写盘异步**: 主线程只入队 (`JsonlSink`), 队列满丢行并计数。后台线程每 64 行或队列空
  200ms 时 flush。

---

## 6. 坑位清单 (每条都踩过或审计过)

### 6.1 混淆名: 只在部署版出现, 开发环境看不见 ⚠️
发布 jar 里 Minecraft 类被 remap 成 intermediary, `getClass().getSimpleName()` 会给出
**不可读的名字**。已修的两处:

| 位置 | 曾经的产出 | 现在 |
|---|---|---|
| `act.screen` | `class_1723` / `class_3858` | `furnace` / `player` (instanceof 判定) |
| `memory` 动物键 | `animal:class_1479` | `animal:minecraft:sheep` (EntityType 反查) |

**规则**: 涉及 Minecraft 自带类型的名字, 一律走 `Registry.X.getId(...)` (方块/物品/实体/
状态效果) 或 `instanceof` 判定 (界面/容器)。**永不**用 `getClass().getSimpleName()`。
AltoClef 自己的类 (任务、链) 不受影响, 可以放心用。

### 6.2 gzip 生命周期: 不收尾 = 整段数据作废 ⚠️
gzip 头是构造时立刻写的 (10 字节), 尾部要 `close()` 才补。所以"只有 10 字节的文件"
= 开了没关, 多数读取器直接报错。20260917-100000-r2 就是这样 (4 个 10 字节文件)。

这也是为什么 `onSessionEnd` 挂在 **4 个**出口 (见 §3), 并且有 JVM shutdown hook。

(2026-09-17 兜底) `JsonlSink` 线程一启动就先落一个 **20 字节的合法空 gzip** (头 10 +
空 deflate 终块 `03 00` + CRC/ISIZE 全 0) 当占位, 首行真正出队时才开流式成员覆盖它。
这样"会话刚建立就被硬杀"留下的也是能正常读出 0 行的文件, 而不是只有 gzip 头的残片。
不走"多成员 gzip (空成员 + 追加真成员)"的写法: 规范允许串接、Java/Python 都读得全,
但 **Windows PowerShell 的 `GZipStream` 只读第一个成员** —— 会把整份语料读成 0 行 (已实测)。

### 6.3 LOS 缓存 = 400ms 感知延迟, 不是瞬时真值
`perceiveSource` 内部的 `isBlockVisible` 复用 `VisibilityHelper._losCache` (400ms TTL +
玩家位移 >2 格失效)。meta.json 里写明了这一点 —— **训练时不要把 perception 当作精确到
tick 的真值**。

### 6.4 录制起点可能早于 bot 启用
`@record on` 只要在游戏内就开始录, 此时 bot 可能还没跑任务 → 前若干帧 `labels` 全空
(实测前 101 帧 ≈ 5 秒)。离线处理时按 `labels.chain == null` 过滤即可。

### 6.5 感知通道的数据量不要靠"关通道"解决
没有通道开关 (曾经的 `PHASE2_*` 常量已删)。要减体积调 `perceptionCaps`
(`recordPerceptionMaxBlocks` / `recordPerceptionRange` / `recordNearScanRadius`)
或关 `recordInventoryPerTick`。

### 6.6 `recordNearScanRadius` 不能随意调大
体积与开销正比于**半径³**且是逐格扫。12 → 20 会让每列扫描量翻 4.6 倍。

---

## 7. 已知缺口 (待办)

| 缺口 | 说明 | 影响 |
|---|---|---|
| ~~听觉通道基本为空~~ **(2026-09-17 已修)** | 改为 `SoundSystemMixin` 在声音引擎总入口截获 (服务端广播/实体语音/自己动作音/环境音), 缓冲 8s+128, 可闻过滤 `d≤16×volume`。SoundTracker 决策语义零改动。 | 已解决 |
| ~~决策诊断日志不入流~~ **(2026-09-21 已修)** | `Debug.logToFile` 单点汇聚插桩 → `diag` 事件 (每文件 250ms + 全局 25 条/s 节流, `diag_dropped` 汇报丢弃)。JumpStuckResolver 强制干预/虚空列拒绝/巡逻候选等决策依据全入流。 | 已解决 |
| ~~BlockTracker 缓存不入流~~ **(2026-09-21 已修)** | 缓存加写版本号 + `dumpCacheForRecord` 快照 → memory 通道 `blockcache` 段 (rev 变化且 ≥3s 才写)。决策候选集 (巡逻/失联远走/收料取最近/"看见森林"判据) 离线可复原。 | 已解决 |
| 无地形/可达性输入 | 感知通道给的是"可见方块", 但 baritone 寻路直接读世界 (可走性/阻挡)。这部分没进数据。 | L3 移动原语学不完整 |
| 合成/物品变化不可直接查 | 依赖背包逐帧 diff (见 §5.7) | 关 `recordInventoryPerTick` 即失效 |
| 瞬时记忆是两套 | 录制器自持的视觉记忆与 `MineAndCollectTask._recentlySeen`/`_seenOreTick` 口径一致但**不同步** | 数据里看不到"任务自己记的那份" |
| `chainPrio: null` 二义 | 见 §4 | 离线分析要写防御性判空 |
| ~~任务内部临时状态~~ **(2026-09-17 部分解决)** | 黑名单 (`blacklist` 事件) 与看门狗结论 (`anomaly` 事件) 已入流; 但任务内部更细的私有租约/冷却 (FIRE 锚点 180s 租约、CursorArbiter 意图租约等) 仍未录 —— 好在任务栈 debug 文案大体表达了它们。 | 极端场景的行为转折仍需从 `d`/`s` 文案推断 |

---

## 8. 排查手册

| 症状 | 先看哪里 |
|---|---|
| 没有任何目录 | `recordDatasetEnabled` 是否为 true; `@record` 的开关行; 是否真的 `inGame()` |
| 有目录但 `.gz` 是 10 字节 | 会话没收尾 (§6.2)。查是否开录后立刻离开世界/退游戏; 钩子是否装上 |
| `frames` 有数据但 `labels` 全空 | bot 没在跑任务 (没启用/空闲) → 正常; 或 `TaskRunner` 被早退 |
| `perception.blocks` 很少 | 看同帧 `stats`: `sightCache` 上不去 = 扫描没跑; `candBlocks` 大而 `shownBlocks` 小 = 被 `maxBlocks` 截断; 全 0 = 玩家不在游戏内 |
| `perception.blocks` 只有眼前一两个方块 | 检查 `stats.slice/slices` 有没有在推进 (分期卡住 → 永远只扫第 0 列) |
| `sounds` 恒空 | 2026-09-17 起源是 SoundSystemMixin 漏斗; 若恒空查 mixin 是否注册 (altoclef.mixins.json) / 游戏里是否真的有声音事件 (走路/挖矿必发声) |
| `memory` 只有 1-2 行 | 记忆真没变 → 正常。若 `rev` 变了但内容空 → 版本号假变化回归 (§5.6) |
| 录制本身拖慢游戏 | `@record` 看有没有"丢弃行"警告 (写盘跟不上); 否则查 `altoclef_jank.log` 的帧阶段耗时 |
| 录制器报异常 | `altoclef_diag.log` 里的 `[Record]` 行 (最多记 3 条, 20 次后自禁用) |

读数据的小工具 (PowerShell, `.gz` 不能直接 Read):

```powershell
function Read-GzLines($p) { $fs=[IO.File]::OpenRead($p); $gz=New-Object IO.Compression.GZipStream($fs,[IO.Compression.CompressionMode]::Decompress); $sr=New-Object IO.StreamReader($gz); $lines=@(); while(($l=$sr.ReadLine()) -ne $null){ $lines+=$l }; $sr.Close(); $fs.Close(); return $lines }
$fr = Read-GzLines "D:\minecraft\.minecraft\record\<会话>\frames.jsonl.gz"
$fr[0] | ConvertFrom-Json
```

---

## 9. 文件地图

| 文件 | 职责 |
|---|---|
| `RecordManager.java` | 生命周期 (开/收/自禁用)、挂载入口 `tick()`、`meta.json`、事件 API、派生事件 |
| `FrameSampler.java` | A/B 通道装配 (时间 + 任务标签), 委托 C/G |
| `StateSampler.java` | C 通道: 玩家状态 + 背包全量 + 状态效果; 物品/效果注册名缓存 |
| `ActionSampler.java` | G 通道: 两套按键 + baritone 导航 + 破坏进度 + 界面类型 + **容器槽位快照 (slots)** |
| `PerceptionSampler.java` | D 通道: 近场/远场分期扫 + 录制专用视觉记忆 + 实体 + **声音 (SoundSystemMixin 喂入的缓冲) + 近域 blockstate (st)** |
| `MemoryRecorder.java` | E 通道: 三块记忆的快照, 只在版本号变化时输出 |
| `JsonlSink.java` | 异步 gzip JSONL 落盘 (队列 + 后台线程 + 丢弃计数) |
| `Json.java` | 手写 JSON 转义/数字格式化 (不引依赖、不建对象树) |

### 被录制器依赖的"读访问器" (改这些要同步看录制器)
`Task.debugString()/debugState()` · `TaskRunner.getCachedPriorities()/getCachedWinnerPriority()/getChains()/getCurrentTaskChain()` ·
`VisibilityHelper.perceiveSource()/getValuableMemoryRevision()/dumpValuableMemory()` ·
`WorldMemory.getMemoryRevision()/dumpMemoryForRecord()` · `KnowledgeMemory.getMemoryRevision()/dumpMemoryForRecord()` ·
`BlockTracker.getCacheRevision()/dumpCacheForRecord()` (2026-09-21 blockcache 快照) ·
`EntityTracker.getAllPerceivedEntities()` · `SoundTracker.dumpRecentSounds()` · `Settings.isRecordDatasetEnabled()` 等录制设置

### 插桩点 (行为代码里被录制器调用的位置)
- 诊断日志: `Debug.logToFile` (全项目唯一汇聚点) → `RecordManager.onDiagLog` →
  `diag` 事件 (内部节流, 未录制零成本早退)
- 记忆写入: `WorldMemory.rememberOre/rememberAnimal/rememberPortal/rememberBed/rememberLostPortal/rememberDeathGrave`
  → `bumpRevision` → `RecordManager.event("memory_write", ...)`
- 记忆读取: `WorldMemory.getNearestOre/Animal/Portal/Bed`、`KnowledgeMemory.getRememberedCenters`
  → `RecordManager.eventThrottled("memory_read", ...)`
- 决策知识 (2026-09-17): `AbstractObjectBlacklist.blackListItem` → `event("blacklist", ...)` ·
  `AnomalyWatchdog.emit` → `event("anomaly", ...)`
- 听觉 (2026-09-17): `mixins/SoundSystemMixin` (SoundSystem.play 总入口) →
  `RecordManager.onSoundPlayed` → `PerceptionSampler.onClientSound` (只喂录制器, 不进 SoundTracker)
- 所有插桩都是 `RecordManager.event*` 内部先判 `_frames == null` 早退, 未录制时零成本。

---

## 10. 构建 / 部署 / 验证

```powershell
# 构建 (JAVA_HOME 在 _altoclef_assets, 不是 altoclef 目录下)
$env:JAVA_HOME="D:\Project\life01\_altoclef_assets\jdk17\jdk-17.0.20+8"
$env:GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"
.\gradlew.bat build -x test --no-daemon      # 产物 build/libs/altoclef-0.4.0.jar

# 部署 (备份 + 覆盖 + md5 校验, 一条链走完)
$stamp = Get-Date -Format "yyyyMMdd_HHmm"
Copy-Item "D:\minecraft\.minecraft\mods\altoclef-0.4.0.jar" "D:\minecraft\.minecraft\mods\altoclef-0.4.0.jar.bak_$stamp" -Force
Copy-Item "d:\Project\life01\altoclef\build\libs\altoclef-0.4.0.jar" "D:\minecraft\.minecraft\mods\altoclef-0.4.0.jar" -Force
# 再对两个路径 Get-FileHash -Algorithm MD5 比对
```

- 部署后**重启客户端**才生效 (游戏运行中覆盖不会被加载)。
- 验证录制真的通了: 开一局 → `@record on` → 等 5 秒 → `@record` 看帧数在涨。
- 验证 jar 真的含新代码: `jar tf <jar> | Select-String "record/"` / `commands/RecordCommand`。

### 采集建议
- 想要有训练价值的语料, 别只录"守炉"这种单状态段 (实测 125 秒里 81% 是熔炉任务、
  `pathing` 只有 2 帧、`breaking` 0 帧 —— L3 标签是常量, 学不到东西)。
  让它跑 `@get iron` 这类会挖矿/跑图/战斗的完整流程。
- 每次改完录制模块, 至少跑一段非熔炉流程验证 `perception.stats.sightCache` 能上到几百以上。

---

## 11. 已见的失败模式 (按时间倒序, 复现时先查这里)

| 日期 | 现象 | 根因 | 修法 |
|---|---|---|---|
| 2026-09-17 | `perception.blocks` 每帧只有 1 个 (熔炉自己), 2495 帧共 2377 个方块 | 候选集用 `BlockTracker.getTrackedBlockTypes()`, 守炉时≈空 | 改为近场全量扫 + 远场稀疏扫, 只过 `perceiveSource` (§5.3) |
| 2026-09-17 | `act.screen` 全是 `class_1723`/`class_3858` | `getClass().getSimpleName()` 在 remap 后拿到 intermediary 名 | instanceof 判定 (§6.1) |
| 2026-09-17 | `memory` 的 animal 键会是 `class_1479` | 同上 (Minecraft 类的 Class) | EntityType 反查注册名 (§6.1) |
| 2026-09-17 | `-r2` 会话 4 个 `.gz` 全是 10 字节 | `!inGame()` 时直接 return 不收尾 + 退游戏无钩子 | 会话边界收尾 + JVM shutdown hook (§6.2) |
| 2026-09-17 | `memory` 第 2 行 `rev.knowledge=1` 但 `knowledge={}` | `ensureCentersLoaded` 无条件推进版本号 | 只在真读进内容时推进 (§5.6) |
| 2026-09-17 | `perception.blocks` 71.6% 帧为 0, 且自 seq≈5200 起**永久**空; `stats.sightCache` 精确卡死 60000 达 1.6 万帧 | ①剪枝用 `Long.MIN_VALUE` 当哨兵 → `now - 哨兵` 溢出为负 → `pruneSight` 一次没跑; ②满上限时静默拒收新坐标 → 记忆彻底冻死 | 剪枝改单调计数 `_slicesSincePrune`; `_sight` 改 accessOrder LRU, 满上限自动淘汰最久未确认条目 (PerceptionSampler) |
| 2026-09-17 | 停 bot 后 41ms 自动开 `-r2`, 站着录了 13 分钟空任务 | 停 bot 是收尾出口但不离开世界, 下一 tick 又自动 `startSession` | `onBotStopped()` 上 `_pausedAfterStop`  (§3 会话边界硬规则) |
| 2026-09-17 | 会话刚建立即被硬杀 → `.gz` 只有 gzip 头 (10 字节), 读取器报错 | 开流即建文件, 尾部只有 `close()` 才补 | 先落 20 字节合法空 gzip 占位, 首行才开真流覆盖 (§6.2) |

---

## 12. 维护检查表

改录制模块时逐条过:

- [ ] 新增字段涉及 Minecraft 自带类型 → 走 `Registry` / `instanceof`, **没有** `getSimpleName()`
- [ ] 新增的 API 调用是**只读**的 (没有 `setTask`/`setDirty`/状态变更)
- [ ] 没有重复调用 `getPriority()` 一类的有副作用判定
- [ ] 新代码全包 try/catch, 失败不能让 tick 抛出去
- [ ] 走的是 `VisibilityHelper.perceiveSource`, 没有第二套可见性判定
- [ ] 新增设置项 → 加字段 + 注释 + getter + 写进 `meta.json` 的 `perceptionCaps`/`settings`
- [ ] 影响协议 (字段/语义/口径) 的改动 → 同步更新本文 §4/§5 + `meta.json` 里的口径说明
- [ ] 新增会话级静态状态 → 在 `startSession` 和 `onSessionEnd` 里复位
- [ ] 构建通过 + 部署 + 实跑一段非熔炉流程, 确认 `sightCache` 上到几百