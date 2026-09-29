# 需求报告：Minecraft 模组接入 AI 聊天室（多 bot 联机）

> 本文档面向 altoclef 模组（`D:\Project\life01\altoclef`，Fabric / MC 1.18.2 / Java 17）。
> 配套的上游项目是 AI 角色扮演聊天室 `d:\Project\chat_and_play`（Next.js）。
> 新会话可直接从"阶段一"开始实施，无需额外背景。

---

## 0. 最终目的（Why）

把 `chat_and_play` 里的多 AI 角色聊天室接入 Minecraft：

- 每个 AI 角色操控一个装了 altoclef 的 Minecraft 客户端（bot），**bot 自己完全有能力自动通关**（`@gamer` 主线任务链已具备）。
- LLM 不接管操作细节，而是充当"灵魂"：让角色**感知自己在游戏里正在干嘛**（粗粒度即可，不追求极致反应速度），并像朋友一样**边通关边聊天**——互相交流、打情骂俏、聊人生聊未来。
- 支持基础协作交互：两个 bot 交换物品、互报坐标、跟随彼此、一起推进主线。合作不需要太深，信息不对称本身就是戏剧情趣的来源。

一句话：**模组负责"会玩"，LLM 负责"像人"。** 本阶段所有技术工作都为这个目的服务。

### 全局约束（贯穿所有阶段，务必遵守）

1. **真实底线**：感知严格过视线（LOS）/视野（FOV）过滤——不在视野里的矿石不报，别的 bot 背包内容不可见。但**自身状态**（背包、坐标、"我在干嘛"）无需视线即可知，如同人不用看也知道自己在挖矿。禁止任何"透视"式信息泄露。
2. **不强制回应**：游戏事件进入聊天后，是否发言、何时发言，交给聊天室既有的过棒 / 沉默 / 空闲退避机制决定。bot 在挖矿时"没空理人"是正确的，不要为"回应义务"加补丁。
3. **LLM 只发高层指令**：不微操。行动面收敛到现成命令（`@gamer` / `@goto` / `@follow` / `@give`），模组内部自己分解执行。
4. **克制即真实**：不为热闹而增加行为。能不加的调度、保险丝、冷却机制都不加。

---

## 1. 为什么先做这一步

多 bot 只能靠**多个客户端连接同一个专用服务端（dedicated server，离线模式）**实现，而非"多开单人存档"。

但当前模组是在单人存档下开发的，存在"服务端逻辑寄生在同一 JVM"的隐含假设（单人的内嵌 integrated server 与客户端同进程）。一旦连上专用服务端，客户端 JVM 不再跑服务端逻辑，**若干自研功能会静默失效**（不报错、不崩溃，但功能报废）。

因此实施顺序为：

```
阶段一：单人 → 专用服务端兼容性修复（本会话核心，必做前置）
阶段二：模组内置 bridge 层（HTTP 服务 + 状态摘要 + 命令入口 + botId）
阶段三：感知/注入对接（数据面主要由阶段二提供，逻辑在 chat_and_play 侧）
```

---

## 2. 阶段一：单人 → 专用服务端兼容修复（本会话重点）

### 2.1 风险扫描结论

- **无启动崩溃级断点**：全仓无 `setBlockState` / `spawnEntity` / `ServerWorld` 强转等服务端写操作；世界修改全走 `ClientPlayerInteractionManager`（客户端 API，天然兼容多人）。所有 `@Mixin` 目标是共享类或客户端类，不会因注入点缺失而崩启动。
- **有 4 处静默失效**，全部集中在自研代码对"单人共享 JVM"的依赖上。修复的共同思路：**凡是需要知道"服务端发生了什么"的地方，改为监听客户端可观测的网络包 / 事件信号。**

### 2.2 🔴 必坏项（需修复）

#### 🔴#1 PlayerDamageMixin 失效 → 死因/敌意记忆三路全灭
- 位置：[PlayerDamageMixin.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/mixins/PlayerDamageMixin.java#L34-L37)，注入 `PlayerEntity#damage` HEAD。
- 类注释（19-23 行）自述依赖"单体内置服务端与客户端共享 JVM，服务端权威伤害必走此链"。连专用服后客户端的 `damage()` 不再被服务端调用。
- 失效后果：**死因记录（PlayerDamageCauseHolder）、"携带珍贵物品"快照、猪灵敌意记忆（HitRegistry）三路全灭**。
- 修复方向（新会话请核实 1.18.2 具体包名）：改用客户端可观测信号——
  - 受伤/血量变化：`ClientPlayNetworkHandler.onHealthUpdate`（HealthChangeS2CPacket），对比前后血量推断受伤。
  - 死亡：`ClientPlayNetworkHandler.onEntityStatus`（DEATH 状态 id=3）。
  - 击杀者/死因文本：`DeathMessageS2CPacket`（死亡播报是聊天文本，可从中解析凶手，用于死因与敌意记忆）。
  - 尽量复用现有 `eventbus/EventBus`，把上述客户端信号转成模组内部事件，下游消费者不改。

#### 🔴#2 死亡墓碑链连锁瘫痪
- 位置：[DeathMenuChain.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/chains/DeathMenuChain.java#L97-L128)。
- 依赖 🔴#1 的 `consumeLastDeathCarriedHighValue()`；上游失效后它恒为 false → 走 `noteDeathWithoutGrave` 分支 → **墓碑永不写入 → DeathRecoveryChain（回 corpse 捡装备）永不激活**。bot 死亡不捡装备 = 自动通关进度永久损失，属刚需。
- 说明：死亡坐标取自客户端 player，多人下本身没问题；重生按钮 `requestRespawn()`（147 行）与自动重连（158-178 行）本就是多人向设计，无需改。
- 修复：🔴#1 修好后此链自然恢复；需**真实联机回归测试**死亡 → 记墓碑 → 跑回捡尸全流程。

#### 🔴#3 WorldIdentity 世界隔离失效（影响面最大，优先修）
- 位置：[WorldIdentity.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/util/helpers/WorldIdentity.java#L38)。
- `MinecraftClient.getServer()` 在多人下恒 null → `saveDirName()` 退化为 `"?"`。五类跨会话记忆（knowledge / selfdug / explored / fortress / portals，调用点约 17 处，见 `trackers/KnowledgeMemory.java`、`trackers/PortalMemoryStore.java` 等）的 key 全部撞成 `w2:?:...`，**不同世界/不同服务器记忆互相污染**；`trackers/WorldStateGuard.java:65` 的切世界清档检测因 key 恒为 "?" 永不触发。
- 注释（26-28 行）已自认"多人服共享 ?，不在修复范围"——现在纳入范围。
- 修复方向：世界 key 改用 `MinecraftClient.getCurrentServerEntry()`（`ServerInfo` 的 address / name）；单人回退现有 `saveDirName()` 逻辑。确保单人和多人各自产出稳定且互不相同的 key。

#### 🔴#4 AnimalDeathMixin 失效（优先级低，不阻塞通关）
- 位置：[AnimalDeathMixin.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/mixins/AnimalDeathMixin.java#L25-L29)，注入 `LivingEntity#onDeath`（服务端生命周期），多人下不触发。
- 后果：动物死亡记忆清理（AnimalMemoryJanitor）失效，"羊坟场"旧 bug 回归（已死动物记忆永久残留）。
- 修复方向：改订阅 `EntityTracker` 实体消失事件，或解析死亡聊天播报。可放最后做。

### 2.3 🟡 可疑项（需实测确认）

- **fabric.mod.json environment**：[fabric.mod.json](file:///D:/Project/life01/altoclef/src/main/resources/fabric.mod.json#L17) 为 `"*"` 但只有客户端入口 `ModInitializer`。若 jar 被误装到服务端，`AltoClef.onInitialize()`（`AltoClef.java:128`）中 `RenderThreadWatchdog.start()`（136 行）会起守护线程，而 `MinecraftClient.getInstance()` 为 null，可能空转/误报。建议：environment 改为 `"client"`，或补一个空的 `DedicatedServerModInitializer`。
- **Butler / WhisperChecker**：[WhisperChecker.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/butler/WhisperChecker.java#L17-L60) 靠正则解析聊天 HUD 字符串识别私聊，无服务端 API 依赖，理论可用；但专用服 whisper 消息格式 / 离线模式差异需实测一次（关注 `whisperFormatDebug`）。白名单按用户名鉴权，不挑单多人（`butler/UserAuth.java`）。
- **record 元数据降级**：[RecordManager.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/record/RecordManager.java#L596-L612) 的 seed / levelName 有 `isInSingleplayer()` 保护，多人下写 "null"——不崩，但训练数据丢失世界身份（与 🔴#3 联动）。修好 #3 后此处应同步用服务器身份填充。

### 2.4 阶段一验收标准

1. 单个装了本 mod 的客户端，成功连接一个 `online-mode=false` 的 Fabric 专用服务端。
2. 在服务器上跑 `@gamer` 主线，能正常推进（不要求通关，验证基本任务链不因多人而断）。
3. bot 死亡后：能记录墓碑、能自动跑回捡装备（验证 🔴#1 + 🔴#2）。
4. 跨会话记忆按世界隔离：换一个服务器/存档，knowledge/portals 等记忆不串档（验证 🔴#3）。
5. 单人存档下上述功能不回退（向后兼容）。

---

## 3. 阶段二：模组内置 bridge 层（阶段一完成后）

模组当前**无任何网络层**（全源码无 Socket/WebSocket/HTTP server）。需新增一个轻量 HTTP 服务，作为聊天室与游戏之间的桥。

### 3.1 技术方案
- 用 JDK 自带 `com.sun.net.httpserver.HttpServer`，**零额外依赖**，只在客户端进程内起（localhost）。
- 端口按 bot 实例分配（如 8761 / 8762 …），或统一端口 + 每请求带 `botId`；由 mod 配置文件（`ConfigHelper` 体系）读取。
- 每个 Prism 实例有独立 `.minecraft` 与 mod 配置目录，端口/botId 天然隔离。

### 3.2 端点契约

| 端点 | 方法 | 用途 | 数据来源 |
|---|---|---|---|
| `/status` | GET | 低频状态摘要（见 3.3） | StateSampler / TaskRunner / ItemStorageTracker |
| `/events` | GET | 拉取自上次以来累积的离散事件（L0） | EventBus 订阅缓冲 |
| `/command` | POST | 下发高层指令（`@goto`/`@follow`/`@give`/`@gamer`…） | 转交 `CommandExecutor` |

### 3.3 状态摘要（关键：降频 + 摘要，不喂全量）
- `record/StateSampler` 是每帧全量 JSON，**不能直接喂 LLM**（太贵）。
- 新增一个 5~10s 低频"摘要"生成器，输出一句中文，例如：
  `正在挖铁矿(3/5)，坐标(120,64,-40)，血量18，有点饿；小影在40格外。`
- 措辞用模板 + 随机措辞池起步（零成本、零延迟），暂不上 LLM 改写。

### 3.4 事件采集（L0）
- 复用 `eventbus/EventBus`，订阅 `TaskFinishedEvent`、`BlockBrokenEvent`、`ChatMessageEvent` 等，缺的补事件类（稀有物入包、维度切换、结构发现）。
- 事件翻译成中文短句，进缓冲队列供 `/events` 拉取。
- 聚合节流：3s 窗口内同类事件合并（"挖到 3 个铁矿" 而非三条）。

### 3.5 阶段二验收标准
1. 浏览器/curl 访问 `http://localhost:8761/status` 返回合法摘要 JSON。
2. `POST /command {"cmd":"@goto 100 64 100"}` 能让 bot 执行。
3. 挖到矿石/完成任务后，`GET /events` 能取到对应中文事件。
4. 多实例并行时，各端口/botId 数据互不串。

---

## 4. 阶段三：感知/注入对接（主要工作在 chat_and_play 侧）

> 记录于此供全局参考，实际改动在 `chat_and_play` 项目，非本 mod 会话范围。

三层感知 × 三种注入（时间尺度对齐聊天室既有的过棒 3s / 思考 1.5s / 退避 ×2ⁿ）：

- **L0 事件层**：即时推送（<1s），走 `injectSystemEvent` 进队列，参与下一自然轮，不插队、不强制醒。强事件（死亡、对方给物/求助）可配 `wakeRole` 开轮。
- **L1 状态层**：拉取式（快照 5~10s），**不作为消息进历史**，在 `executeMemberChat` 组装 prompt 时才拉，渲染成 A 类系统消息【游戏状态】。避免逐轮堆积污染历史。
- **L2 叙事层**：分钟级，并入 director 循环产出"游戏近况"，作为**独立字段**（不写 `current_situation`，后者归社交情境所有权）。

桥接落点：`chat_and_play` 新增 `/api/game/*` 路由代理模组 HTTP（localhost），前端轮询后注入 room-context。

---

## 5. 待用户拍板的开放决策

1. **bot 拓扑**：v1 是否先单 bot 打通全链路（推荐），再扩到多实例？
2. **强事件清单**：哪些事件允许 `wakeRole` 强制开轮？建议初始仅"死亡"和"对方给物/求助"。
3. **游戏内聊天镜像**：聊天室发言是否同步发到 MC 公屏（`MessageSender` 一行调用），供人类玩家围观。
4. **L1 轮首读取舍**：接受"说话说到一半时被打断"这类事中感知缺失（靠 L0 下一轮补）？

---

## 6. 本会话（阶段一）执行建议顺序

```
1. 修 🔴#3 WorldIdentity（最小、最独立，其余验证都受益）
2. 修 🔴#1 PlayerDamageMixin（客户端信号化）→ 连带验证 🔴#2 死亡链恢复
3. 改 fabric.mod.json environment（🟡，顺手）
4. 起离线专用服务端，实测单 bot：@gamer 推进 + 死亡捡尸 + 记忆隔离
5. 视实测结果处理 🟡 Butler 私信格式 / record 元数据
6. 🔴#4 AnimalDeathMixin（不阻塞，可最后）
```

> 提交规范：按项目规则，每完成一处修改即提交一次。

---

## 附录 A：阶段一扫描勘误与补充（2026-09-29 取证）

> 取证方法：对 `minecraft-mapped.jar`（yarn `1.18.2+build.3`）逐条 `javap -c -p` 反查
> 调用方与被调链，不依赖记忆或推测。**正文 2.2 / 2.3 的结论以本附录为准。**

### A.1 ❌ 勘误：🔴#4 AnimalDeathMixin **没有失效** —— 建议从"必坏项"移除

正文称它注入"服务端生命周期，多人下不触发"，实测**不成立**：`LivingEntity#onDeath`
在客户端会被调用。调用链（字节码逐跳核实）：

```
ClientPlayNetworkHandler.onEntityStatus(EntityStatusS2CPacket)
   → Entity.handleStatus(B)                     // invokevirtual，第 151 偏移
   → LivingEntity.handleStatus(byte)            // tableswitch case 3（死亡状态 id=3）:
        446: getDeathSound() / playSound(...)   // 播死亡音
        485: instanceof PlayerEntity → ifne     // 玩家被跳过
        492: setHealth(0)
        501: invokevirtual onDeath(DamageSource.GENERIC)
```

结论：动物死亡时客户端仍走 `onDeath`（`AnimalMemoryJanitor` 照旧被调用），
**范围收窄为"客户端追踪范围内的实体"** —— 而该 janitor 清的就是"bot 附近被杀掉的羊"，
正是这个范围。
两点细节：① 客户端那次的 `source` 恒为 `DamageSource.GENERIC`（该 mixin 不读 source，无影响）；
② 待实测确认 `handleStatus` 到达时 `entity.getBlockPos()` 仍是死亡坐标（建议加一条零行为日志）。
⇒ **本条改为"实测确认即可，预期无需改代码"**，可放到阶段一末尾甚至跳过。

### A.2 🆕 遗漏：`BlockBrokenEvent` 在专用服务端下必死

`BlockModifiedByPlayerMixin` 注入 `Block#onBreak`，而该方法的唯一调用方是
`ServerPlayerInteractionManager.tryBreakBlock`（javap 全仓反查，客户端无一调用方）
⇒ 专用服下客户端 JVM 不执行 ⇒ **`BlockBrokenEvent` 永不发布**。
影响面：① 唯一消费者 `ReplaceBlocksTask`；② **本报告 3.4 明确把 `BlockBrokenEvent`
列为 `/events` 数据源** —— 阶段二开工前必须换挂点（客户端挖掘完成路径）。

对照组（**正常**，不必改）：`WorldBlockModifiedMixin` 注入的 `World#onBlockChanged`
客户端可达 —— `ClientPlayNetworkHandler.onBlockUpdate` → `World.setBlockState(...)`
尾部**无条件** `invokevirtual onBlockChanged`，且 `ClientWorld` **未覆写** `setBlockState`
（只覆写 `setBlockStateWithoutNeighborUpdates`）⇒ `BlockPlaceEvent`、`BlockTracker.addBlock`
在多人下工作正常。

### A.3 🆕 遗漏：🔴#1 还有**第 4 个消费方**（行为级退化，不只是记忆质量）

正文只列了"死因 / 珍贵物快照 / 猪灵敌意记忆"三路。`PlayerDamageMixin` 还喂
`HitRegistry._recentAttackerUntil`（"实际伤害过 bot"实锤，窗口 60s）。专用服下恒空
⇒ 三处防御门退化：

| 位置 | 语义 | 多人下后果 |
|---|---|---|
| `MobDefenseChain.java:1427` | 骷髅 must-kill 的**硬门**（`if (!hasRecentlyDamagedPlayer) continue`） | **永久关闭**：被看不见的骷髅射中也不会主动去杀 |
| `MobDefenseChain.java:692` | 交战证据 = 可见 **或** 被打过 | 退化为"仅肉眼可见" |
| `FurnaceSmeltGuardTask.java:260` | 离炉派发证据门（同上 OR） | 同上 |

⇒ 多人下**防御侵略性整体下降**。需要决策：接受降级，或补 A.4 的归因手段。

### A.4 🆕 遗漏：🔴#1 的修法缺口 —— 1.18.2 客户端**拿不到"攻击者是谁"**

正文说"用 `onHealthUpdate` 对比前后血量推断受伤"，但没说攻击者怎么拿。
1.18.2 **没有** `EntityDamageS2CPacket`（1.19.4 才引入），也没有把"谁打了我"
同步给受害者的包。客户端可用信号实测清单：

| 信号 | 入口 | 能给什么 | 能否归因攻击者 |
|---|---|---|---|
| 血量/饥饿变化 | `HealthUpdateS2CPacket` → `ClientPlayerEntity.updateHealth(F)` | 受伤边沿 | ❌ |
| 死亡播报 | `DeathMessageS2CPacket(entityId, killerId, message)` → `onDeathMessage` | 精确 `killerId` + 死因文本 | ✅（**该包正是死亡屏的来源**，故 DeathMenuChain 的 2s 等待逻辑不变） |
| 死因名 | 同上 `getMessage()`：`TranslatableText.getKey()` 形如 `death.attack.lava` | 等价于旧 `DamageSource.getName()` 的剥前缀口径 | ✅（不受客户端语言影响） |
| 实体状态 | `EntityStatusS2CPacket` → `handleStatus` | 实体死亡（见 A.1）；**玩家分支被跳过** | — |
| 投掷物 | `ProjectileEntity.getOwner()` | —— | ❌ 1.18.2 里 owner 解析被 `world instanceof ServerWorld` 门死，客户端恒 null（除非自己射的） |

⇒ 可行的归因分层：**① 死亡 → `killerId`（权威）**；② 远程命中 → 不可得（`getOwner()`
客户端为 null），只能靠箭矢轨迹反向延长线做几何猜测（未实施）；③ 非致命近战 → 无权威来源。
**建议不做猜测式归因**（"最近敌对实体"会把 20260924 修掉的"无缘无故锁敌"复活）；
若确需恢复近战场景（如 20260915 那次猪灵贴脸打死不还手），可考虑
`EntityAnimationS2CPacket`（挥手臂同步）+ 同拍掉血的相关性，属额外机制，阶段一不引入。

### A.5 本会话已实施

| # | 内容 | commit | jar md5 / 备份 |
|---|---|---|---|
| 🔴#3 | `WorldIdentity.saveDirName()` 新增第二解析级：集成服务器不可用 → `getCurrentServerEntry().address`（空退 `name`），前缀 `srv:`；单人路径不变 | `13d07184` | `a71092088a8addfb72318804656e8329`，bak `..._20260929_1605` |
| 🔴#1 | 新增 `util/helpers/ClientDamageSensor`（客户端通道）：死亡播报还原死因（translatable key 剥前缀）+ `killerId` 喂 `HitRegistry`；背包珍贵物快照改为「存活期 1Hz 滚动 + 非致命掉血即刷 + 血量为 0 不刷」（避免读到服务端已清空的背包）；`ClientPlayNetworkHandlerMixin` 加挂 `onHealthUpdate` / `onDeathMessage`；`PlayerDamageMixin` 保留（单人权威路径）并把扫描实现收敛到 `ClientDamageSensor.scanCarriedHighValue` 单一判据 | 见下方提交 | 见下方 |

> 🔴#2（死亡墓碑链）无需改代码：它消费的 `consumeLastDeathCarriedHighValue()`
> 由上述通道喂满即可自然恢复；验收仍走 2.4 第 3 条实测。

### A.6 复核通过项（原判断无误，省得再查）

- 全仓非 mixin 代码**零** `net.minecraft.server.*` 引用；无 `setBlockState` / `spawnEntity` 直写。
- `fabric.mod.json` `environment:"*"` + 仅 `main` 入口：若误装服务端，`RenderThreadWatchdog`
  起线程而 `MinecraftClient.getInstance()` 为 null —— 原判断成立，建议改 `"client"`。
- `PlayerCollidesWithEntityMixin`（拾取物）挂 `PlayerEntity.tickMovement → collideWithEntity`，
  客户端会执行，且已用 `instanceof ClientPlayerEntity` 过滤 ⇒ 多人正常。
- `GameOverlayMixin`（`InGameHud.setOverlayMessage`）：服务端 `sendMessage(..., overlay=true)`
  同样走 `GameMessageS2CPacket` ⇒ `PlaceBedAndSetSpawnTask` 的睡觉失败检测照旧可用。
- `DeathMenuChain` 全程走客户端 GUI（`DeathScreen` / `DisconnectedScreen` / `MultiplayerScreen`），
  重生与自动重连本就多人向，无需改。
- `ChatReadMixin` 挂的是 `ChatHudListener.onChatMessage`（**玩家聊天**）；服务端系统消息
  走 `onGameMessage` ⇒ 若阶段三要让"死亡/成就播报"进聊天，需要另挂 `onGameMessage`
  （**单人也一样**，非多人问题，但阶段二/三要用到，先记在这）。
