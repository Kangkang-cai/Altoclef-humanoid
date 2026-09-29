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
- **静默失效清单（2026-09-29 订正）**：4 处 —— 🔴#1 `PlayerEntity#damage`、🔴#2（#1 的下游，不需单独改代码）、🔴#3 `MinecraftClient.getServer()`、🔴#5 `Block#onBreak`；
  原先列在清单里的 🟢#4 `LivingEntity#onDeath` 经字节码复核为**误报**（客户端可达）。
  共同思路不变：**凡是需要知道"服务端发生了什么"的地方，改为监听客户端可观测的网络包 / 事件信号。**

### 2.2 🔴 必坏项（需修复）

#### 🔴#1 PlayerDamageMixin 失效 → 死因/敌意记忆**四路全灭**（2026-09-29 已修复）
- 位置：[PlayerDamageMixin.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/mixins/PlayerDamageMixin.java#L34-L37)，注入 `PlayerEntity#damage` HEAD。
- 类注释自述依赖"单体内置服务端与客户端共享 JVM"。连专用服后客户端的 `damage()` 不再被服务端调用（javap：`damage` 只被服务端伤害结算链调用）。
- **失效后果（2026-09-29 补齐为四路，原文档只列了三路）**：
  1. 死因记录（`PlayerDamageCauseHolder`）—— `DeathRecoveryChain` 据此判断 grave 是否"放弃拾取"（只认精确 `drown` / `lava`）；
  2. "携带珍贵物品"快照 —— 决定墓碑写不写（🔴#2 的直接上游）；
  3. 猪灵敌意记忆（`HitRegistry` 反向激怒）；
  4. 🆕 **`HitRegistry._recentAttackerUntil`（"实际伤害过 bot"实锤，窗口 60s）** —— 三处防御门随之退化：
     `MobDefenseChain:1427` 骷髅 must-kill 的**硬门**（`if (!hasRecentlyDamagedPlayer) continue`）在多人下恒不满足 ⇒ **永久关闭**；
     `MobDefenseChain:692`、`FurnaceSmeltGuardTask:260` 两处"可见 **或** 被打过"退化为"仅肉眼可见"。
     ⇒ 多人下**防御侵略性整体下降**（行为级退化，不只是记忆质量）。
- **1.18.2 客户端可用信号（已逐条 javap 核实，包名定稿）**：

  | 信号 | 入口 | 能得到什么 |
  |---|---|---|
  | 血量变化 | `onHealthUpdate(HealthUpdateS2CPacket)` → `ClientPlayerEntity.updateHealth(F)` | 受伤边沿；**不含攻击者** |
  | 死亡播报 | `onDeathMessage(DeathMessageS2CPacket)` | `getEntityId()` / `getKillerId()` / `getMessage()`；**该包正是死亡屏的来源** ⇒ DeathMenuChain 的 2s 等待时序不变 |
  | 死因名 | 同上 `getMessage()` 是 `TranslatableText`，`getKey()` 形如 `death.attack.lava` | 与 `DamageSource.getName()` 剥前缀同口径（不受客户端语言影响） |
  | 实体状态 | `onEntityStatus` → `handleStatus` | 实体死亡（见 🟢#4）；**玩家分支被跳过** |
  | 投掷物 | `ProjectileEntity.getOwner()` | ❌ 客户端恒 null（`ownerUuid` 解析被 `world instanceof ServerWorld` 门死）⇒ 远程射手无法归因 |

- **修复方向**：客户端信号落在既有 `ClientPlayNetworkHandlerMixin`（加挂 `onHealthUpdate` / `onDeathMessage`），
  逻辑放普通类 `util/helpers/ClientDamageSensor`（Mixin 类不能有 public static 辅助方法 —— 2026-09-16 启动崩溃先例）。
  ⚠️ **背包珍贵物不要在"血量归零那一刻"扫**：服务端先 `dropInventory()` 再同步血量，发包先后不保证，
  时点扫描会读到已清空的背包 ⇒ 墓碑丢失。改为「存活期 1Hz 滚动 + 非致命掉血即刷 + 血量为 0 不刷」。
- **降级决策（2026-09-29 用户拍板）**：非致命伤害的攻击者归因，本次不做。
  理由：猜测式归因（"最近敌对实体"）会复活 20260924 修掉的"无缘无故锁敌"，风险大于收益；
  且死亡 → 墓碑 → 捡尸链已兜底装备损失，骷髅多死几只不伤主线。
  **触发条件（到期再评审）**：若实测中"骷髅类死亡频率明显拉低 `@gamer` 通关率"，
  再单独立项评审「挥臂动画包（`EntityAnimationS2CPacket`）+ 同拍掉血」的相关性归因，
  作为**独立机制**做，不塞进现有修复。
- 状态：**已实施**（`fe83ec6d`），取证与应见判据见附录 A.4 / A.5。

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

#### 🟢#4 ~~AnimalDeathMixin 失效~~ → **2026-09-29 勘误：误报，从必坏清单剔除**
- 位置不变：[AnimalDeathMixin.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/mixins/AnimalDeathMixin.java#L25-L29)，注入 `LivingEntity#onDeath`。
- **原判断"服务端生命周期，多人下不触发"不成立**：`LivingEntity#onDeath` 客户端可达。
  证据链（javap 于 1.18.2 mapped jar）：`ClientPlayNetworkHandler.onEntityStatus(EntityStatusS2CPacket)`
  → `Entity.handleStatus(B)` → `LivingEntity.handleStatus` 的 `tableswitch case 3`（死亡状态 id=3）分支：
  播死亡音 → `instanceof PlayerEntity → ifne`（跳过玩家）→ `setHealth(0)` → **`onDeath(DamageSource.GENERIC)`**。
- 结论：动物死亡记忆清理在多人下**仍然工作**。范围收窄为"客户端追踪范围内的实体"，
  正是"bot 附近被杀掉的动物"这一 janitor 关心的范围；客户端那次 `source` 恒为 `GENERIC`（该 mixin 不读 source，无影响）。
- 唯一待实测点：`handleStatus` 到达时 `entity.getBlockPos()` 是否仍是死亡坐标（建议加一条零行为日志）。
- 行动：**无需改代码**，放到阶段一末尾顺带确认即可。

#### 🔴#5 BlockBrokenEvent 失效（2026-09-29 补入必坏清单）
- 位置：原 [BlockModifiedByPlayerMixin.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/mixins/BlockModifiedByPlayerMixin.java)，注入 `Block#onBreak`。
- 根因：`Block#onBreak` 的唯一调用方是 `ServerPlayerInteractionManager.tryBreakBlock`（javap 全仓反查，客户端零调用方）
  ⇒ 专用服务端下客户端 JVM 不执行，**事件永不发布**。
- 影响：① 唯一消费者 `ReplaceBlocksTask`；② **§3.4 把 `BlockBrokenEvent` 列为 `/events` 数据源** —— 阶段二开工前必须换挂点。
- **修复方向（2026-09-29 用户拍板挂点）**：改用同族的 `World#onBlockChanged`（已验证客户端可达），
  判定"非空气非流体 → 空气或流体"的**消失跃迁**；只挂客户端世界（单人时内嵌服务端世界也走 `setBlockState`，不加闸会重复发）。
  事件语义收窄为"**方块消失**"，**不区分原因**（活塞 / 床爆 / 流水淹没 / 沙砾下落一律算）—— 上层不关心谁弄没的。
  `BlockBrokenEvent.player` 改为恒 `null`（归因不可得，消费方不得用本地玩家冒充）。
  ⚠️ **采集类事件不要走这个事件**：见 §3.4（"方块消失 ≠ 物品到手"）。

### 2.3 🟡 可疑项（需实测确认）

- **fabric.mod.json environment**：原为 `"*"` 但只有客户端入口 `ModInitializer`；若 jar 被误装到服务端，`AltoClef.onInitialize()` 中 `RenderThreadWatchdog.start()` 会起守护线程，而 `MinecraftClient.getInstance()` 为 null，可能空转/误报。
  **✅ 2026-09-29 已改为 `"client"`**（`98f1f333`）—— 选"改 environment"而非"补空 `DedicatedServerModInitializer`"：本 mod 全部功能都在客户端侧，服务端零职责。
- **Butler / WhisperChecker**：[WhisperChecker.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/butler/WhisperChecker.java#L17-L60) 靠正则解析聊天 HUD 字符串识别私聊，无服务端 API 依赖，理论可用；但专用服 whisper 消息格式 / 离线模式差异需实测一次（关注 `whisperFormatDebug`）。白名单按用户名鉴权，不挑单多人（`butler/UserAuth.java`）。**仍待实测**（并入阶段一实机验证一起做）。
- **record 元数据降级**：[RecordManager.java](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/record/RecordManager.java#L596-L612) 的 seed / levelName 有 `isInSingleplayer()` 保护，多人下写 "null"——不崩，但训练数据丢失世界身份（与 🔴#3 联动）。
  **✅ 2026-09-29 已修**（`882b72e1`）：`world` 对象新增 `"worldId"` 字段 = `WorldIdentity.saveDirName()`（单人=存档目录名 / 多人=`srv:<地址>`），与跨会话记忆 key 同口径，训练数据 ↔ 记忆日志可按世界 join；
  `levelName` 语义不动（存档显示名，多人下仍 null），`seed` 客户端确实拿不到 ⇒ 保持 `"null"` 不伪造。
- 🆕 **聊天通道粒度**：[ChatReadMixin](file:///D:/Project/life01/altoclef/src/main/java/adris/altoclef/mixins/ChatReadMixin.java) 挂的是 `ChatHudListener.onChatMessage`（**玩家聊天**）；服务端系统消息（死亡播报、成就、命令反馈）走 `onGameMessage`，`ChatMessageEvent` 收不到。
  这不是多人引入的问题（单人也一样），但**阶段三要让"游戏事件进聊天"就必须补挂 `onGameMessage`**（或改从 `DeathMessageS2CPacket` 等专用包取），先记在这。

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
- **事件源选择原则（2026-09-29 用户拍板）**：
  - **采集类事件（"挖到铁矿"）以【背包增量】为源**，不要用方块破坏 —— 方块消失 ≠ 物品到手
    （熔炉烧炼、爆炸掉落、别人挖的、经验粒都不是"我挖到了"）；背包增量才是"挖到了"的准确信号。
    现有可用的增量源：`ItemStorageTracker`（物品库存）/`PlayerInventory` 槽位 diff。
  - **`BlockBrokenEvent` 只保留给"功能方块被破坏"这类少数场景**（箱子/熔炉/传送门/火把被砸），
    且语义已收窄为"方块消失"、`player` 恒 `null`（见 🔴#5）—— 别用它推断"谁挖到了什么"。
- 事件翻译成中文短句，进缓冲队列供 `/events` 拉取。
- 聚合节流：3s 窗口内同类事件合并（"挖到 3 个铁矿" 而非三条）。
- ⚠️ 事件源现状（2026-09-29 订正）：`BlockBrokenEvent` 已改挂客户端可达点（🔴#5 修复后可用）；
  `ChatMessageEvent` 只覆盖玩家聊天，系统消息需另挂 `onGameMessage`（见 §2.3「聊天通道粒度」）。

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

## 6. 本会话（阶段一）执行顺序（2026-09-29 更新为实际状态）

```
✅ 1. 修 🔴#3 WorldIdentity（13d07184：srv: 口径）
✅ 2. 修 🔴#1 PlayerDamageMixin 客户端信号化（fe83ec6d）→ 🔴#2 墓碑链无需改代码，待实测
✅ 3. 修 🔴#5 BlockBrokenEvent 换挂点（ce25e304，用户定：World#onBlockChanged + "方块消失"语义）
✅ 4. 改 fabric.mod.json environment → "client"（98f1f333）
✅ 5. record meta.json 补 worldId 世界身份（882b72e1）
⏳ 6. 起离线专用服务端，实测单 bot：@gamer 推进 + 死亡捡尸 + 记忆隔离 + Butler 私信格式
      （Butler 需服务端环境，与实机验证合并做；🟢#4 也在此顺带确认 pos 有效性）
✖️ 7. 原 🔴#4 AnimalDeathMixin —— 已确认为误报，无需改
```

> 提交规范：按项目规则，每完成一处修改即提交一次（本轮 5 项各一次提交，jar 每次部署前后备份）。

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
列为 `/events` 数据源** —— 阶段二开工前必须换挂点。
**✅ 2026-09-29 已按用户拍板换挂点**（`ce25e304`）：改用下方已验证客户端可达的
`World#onBlockChanged`，判据"非空气非流体 → 空气或流体"、只发客户端世界、语义收窄为
"方块消失"（不区分活塞/床爆/流水/沙砾）、`player` 恒 `null`；采集类事件改以背包增量为源（§3.4）。

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

> 正文 §2.2 / §2.3 / §3.4 / §6 已按本附录订正（2026-09-29），本附录保留为**取证记录**（证据链、javap 结论、决策理由），两处口径一致。

| # | 内容 | commit | jar md5 / 备份 |
|---|---|---|---|
| 🔴#3 | `WorldIdentity.saveDirName()` 新增第二解析级：集成服务器不可用 → `getCurrentServerEntry().address`（空退 `name`），前缀 `srv:`；单人路径不变 | `13d07184` | `a71092088a8addfb72318804656e8329`，bak `..._20260929_1605` |
| 🔴#1 | 新增 `util/helpers/ClientDamageSensor`（客户端通道）：死亡播报还原死因（translatable key 剥前缀）+ `killerId` 喂 `HitRegistry`；背包珍贵物快照改为「存活期 1Hz 滚动 + 非致命掉血即刷 + 血量为 0 不刷」（避免读到服务端已清空的背包）；`ClientPlayNetworkHandlerMixin` 加挂 `onHealthUpdate` / `onDeathMessage`；`PlayerDamageMixin` 保留（单人权威路径）并把扫描实现收敛到 `ClientDamageSensor.scanCarriedHighValue` 单一判据 | `fe83ec6d` | `fb2ad714a3307f424d18fb09b0e617ff`，bak `..._20260929_1611` |
| 🔴#5 | 删 `BlockModifiedByPlayerMixin`（注入服务端路径 `Block#onBreak`）+ 移出 `altoclef.mixins.json`；`BlockBrokenEvent` 改由 `WorldBlockModifiedMixin`（`World#onBlockChanged`）发布，判据「非空气非流体 → 空气或流体」、**只发客户端世界**；事件语义收窄为"方块消失"、`player` 恒 `null`；`ReplaceBlocksTask` 改 null 容错 | `ce25e304` | ↑ 同一 jar |
| 🟡 | `fabric.mod.json` `environment: "*"` → `"client"` | `98f1f333` | ↑ 同一 jar |
| 🟡 | `record/meta.json` 的 `world` 对象新增 `worldId`（= `WorldIdentity.saveDirName()`，与记忆 key 同口径） | `882b72e1` | `05f0b293c43d658bf17731fc3309b33e`，bak `..._20260929_1622` |

> 🔴#2（死亡墓碑链）无需改代码：它消费的 `consumeLastDeathCarriedHighValue()`
> 由上述通道喂满即可自然恢复；验收仍走 2.4 第 3 条实测。

### A.6 复核通过项（原判断无误，省得再查）

- 全仓非 mixin 代码**零** `net.minecraft.server.*` 引用；无 `setBlockState` / `spawnEntity` 直写。
- `fabric.mod.json` `environment:"*"` + 仅 `main` 入口：若误装服务端，`RenderThreadWatchdog`
  起线程而 `MinecraftClient.getInstance()` 为 null —— 原判断成立。**✅ 已改为 `"client"`（`98f1f333`）**。
- `PlayerCollidesWithEntityMixin`（拾取物）挂 `PlayerEntity.tickMovement → collideWithEntity`，
  客户端会执行，且已用 `instanceof ClientPlayerEntity` 过滤 ⇒ 多人正常。
- `GameOverlayMixin`（`InGameHud.setOverlayMessage`）：服务端 `sendMessage(..., overlay=true)`
  同样走 `GameMessageS2CPacket` ⇒ `PlaceBedAndSetSpawnTask` 的睡觉失败检测照旧可用。
- `DeathMenuChain` 全程走客户端 GUI（`DeathScreen` / `DisconnectedScreen` / `MultiplayerScreen`），
  重生与自动重连本就多人向，无需改。
- `ChatReadMixin` 挂的是 `ChatHudListener.onChatMessage`（**玩家聊天**）；服务端系统消息
  走 `onGameMessage` ⇒ 若阶段三要让"死亡/成就播报"进聊天，需要另挂 `onGameMessage`
  （**单人也一样**，非多人问题，但阶段二/三要用到，先记在这）。
