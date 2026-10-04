# AI 角色协调原语（`@sleep` / `@quota` / `/status` 玩家维度）

> 2026-10-03 实现。上游需求：用户现场反馈——chat_and_play 实测时"一起睡觉难"，
> 以及"烈焰棒需求，是不是不需要每个 bot 都获得 8 根，但不加控制每个 bot 都会尝试获得 8 根？"
>
> 交付对象：**chat_and_play（Next.js 对端）**。
>
> 📘 **要查"有哪些命令、语法、回执长什么样" → 看 `DOC_bridge_commands.md`（命令对接手册）。**
> 本文件只讲"为什么这么设计、踩了哪些坑"。

---

## 0. 结论摘要

1. **`@quota`（需求份额，新）**：AI 可以改"本 bot 该凑多少"，从而做**分工**。
   这是本次收益最大的一条——它解掉了主线需求"私有且闭合"的根。
2. **`@sleep`（睡觉三态，新）**：`auto` / `bed-only` / `wait-dawn`，把"要不要为睡一觉出门"
   交还给 AI。
3. **`/status` 新增 `onlinePlayers` / `sleepingPlayers`（只读）**：多人一起睡的前提是知道"还差谁"。
4. **`@forgetmem`（记忆作废，新）**：作废 `KnowledgeMemory` 的自动结构记忆 —— 此前 bot 能自动**记住**却**无法作废**（见 §6）。
5. **另修一处判据缺陷**：要塞"记忆命中 → 免检眼量"的旁路把 `STRONGHOLD`（丢眼走廊）与 `END_PORTAL`（亲见门框）当同一件事 ⇒ 眼不够也会先白跑一趟地牢。已收紧为只认 `END_PORTAL`（见 §7）。
6. `@say`（游戏内说话）**不做** —— 对端已有独特通信通道。

---

## 1. 问题：主线需求是私有且闭合的（代码取证）

| 事实 | 证据 |
|---|---|
| 目标棒数 = `ceil(targetEyes/2)` = **8 根** | `BeatMinecraft2Task.java:2309` |
| 是**绝对持有量目标**，且刻意**不扣**背包已有 | 同处 0919 注释（2300–2305） |
| 每个 bot **只读自己的背包** | `BeatMinecraft2Task.java:2335` `mod.getItemStorage()` |
| **全仓没有**任何"需求账本 / 跨 bot 分摊"结构 | grep `quota / sharedDemand / teamTotal` → 只命中无关本地 ledger |
| 目标数来自**每个实例自己的** `beat_minecraft.json` | `BeatMinecraftConfig.java:4`；三处该文件均为空 `{ }` ⇒ 三实例全部 targetEyes=16 |

**后果**：3 个 bot 跑主线 → 各自独立冲 8 根 = 最多 24 根，团队实际只需 ≈8 根（16 眼 ÷ 2）。
对端此前只能从 `/status.mainlineProgress` 看到 `下界·烈焰棒N/8`，**读得到、改不了**。

---

## 2. `@quota` —— 需求份额（新）

### 2.1 语义

**某物品的"绝对持有目标"**。设了 = 本 bot 只负责凑够这个数；没设 = 走主线原有口径。

| 命令 | 含义 |
|---|---|
| `@quota blaze_rod 3` | 我只负责 3 根烈焰棒（凑够就停，不再为 blaze 下界） |
| `@quota blaze_rod 0` | 烈焰棒归别人，我一点不碰 |
| `@quota ender_pearl 16` | 我负责 16 颗末影珍珠 |
| `@quota ender_eye 0` | **总份额归零**：整条末影之眼线全停（棒 / 粉 / 珍珠都不再触发） |
| `@quota blaze_rod -1` | 取消该物品份额，回主线默认 |
| `@quota all -1` | 清空全部份额 |

覆盖三条**独立**需求线：`blaze_rod`（棒）、`ender_pearl`（珍珠）、`ender_eye`（总份额 = 眼数）。
派生物（烈焰粉）不单独设，由棒折算（1 棒 = 2 粉，与主线固有口径一致）。

物品 id 写法：`blaze_rod` 与 `minecraft:blaze_rod` 均可。

### 2.2 分段示例（3 bot 分摊）

```
bot_1: @quota ender_eye 0     @quota blaze_rod 8     # 专攻烈焰棒
bot_2: @quota ender_eye 0     @quota ender_pearl 18  # 专攻末影珍珠
bot_3: 不设（走默认 16 眼）                          # 兜底/总装
```

### 2.3 实现边界（**对端必须知道**）

- 覆盖点**只有一处**：`BeatMinecraft2Task.getEyesOfEnderTask`（全仓唯一的需求计算处）。
  未设配额时该函数**逐字节走原逻辑**，零影响。
- 只影响**收集**，不改传送门插框逻辑（`eyesNeeded` 那套），也不改定位/结构搜索。
- 跨世界/跨会话**保留**（AI 的显式分派）。复位用 `@quota all -1`。
- ⚠ **裸的 `@quota blaze_rod 0` 单独用**：本 bot 会退化为"只收珍珠"。要真正"这块完全不做"，
  建议**同时** `@quota ender_eye 0`。

---

## 3. `@sleep` —— 睡觉三态（新）

| mode | 语义 | 出口 |
|---|---|---|
| `auto`（默认） | 有床用床，没床就地找/造（等价原主线行为） | 天亮 / 新指令 |
| `bed-only` | **只用现成的床**：手里有床 或 24 格内有已知床方块才启动 | 立即拒绝并回话（不进远征） |
| `wait-dawn` | **不睡**，站桩熬到天亮（`WaitForDawnTask`） | 天亮 / 新指令 |

- 动机：主线 `SleepThroughNightTask` 在没床时会派发 `CollectBedTask`（96 格找床 / 羊毛+木板造床）
  = **时长不可预估的远征**，与当初禁掉 `@get` 同型。三态把"要不要为睡一觉出门"交还 AI。
- `wait-dawn` 是多人凑不齐全员入睡时的正解（躺着等天亮 = 站着等天亮，但不会把 bot 钉在床边）。
- **起床/中止不需要新命令**：任何新行动（`@idle` / `@goto` …）会顶掉当前任务。

---

## 4. `/status` 新增字段（只读）

| 字段 | 类型 | 语义 | 空时 |
|---|---|---|---|
| `onlinePlayers` | string | 服务器在线玩家名（tab 列表），逗号分隔 | **整键省略** |
| `sleepingPlayers` | string | 可见范围内**正在睡觉**的玩家名，逗号分隔 | **整键省略** |

⚠ `sleepingPlayers` 只统计**已加载实体**的玩家——追踪范围外（第三方服默认 48 格）拿不到姿态，
所以它是"可见范围内谁在睡"，**不是**服务器全量睡况。判断"全员是否入睡"时对端要意识到这个盲区。

多人和睡觉的标准编排：读 `timeOfDay == night` + `sleepingPlayers` → 给其余 bot 发 `@sleep auto` →
轮询直到 `sleepingPlayers` 覆盖全部 `onlinePlayers`（或已跳夜）。

---

## 5. 白名单变更

`BridgeConfig.allowedCommands` 默认 **9 → 12**：新增 `sleep`、`quota`、`forgetmem`。

三处已落盘的 `bridge.json` **都只写了 `port`/`botId`，没有 `allowedCommands` 数组**，
所以走默认值 —— **不需要改三处配置文件**。

---

## 6. `@forgetmem` —— 自动结构记忆作废（新）

### 6.1 为什么

`KnowledgeMemory` 会自动记住"亲眼确认过"的结构中心（跨会话落盘 `logs/altoclef/memory/knowledge.log`，按 `存档:维度` 隔离），
但**此前没有任何手段作废**。若某条记忆本身记错了（投掷异常 / 地形噪声把落点确认到错处），
bot 会**跨会话一直信**，每次重启都往错地方跑。`@forget` 只管**命名地标**（`NamedLandmarks`），
且原定稿（`mod-bridge-landmark.md` §7.2）明确写着"KnowledgeMemory 永不可经命令删除" ——
**2026-10-03 用户拍板推翻该条**，理由是上面这个不对称。

### 6.2 用法

| 命令 | 含义 |
|---|---|
| `@forgetmem stronghold` | 作废本存档的**要塞**记忆（丢眼确认的走廊坐标） |
| `@forgetmem end_portal` | 作废本存档的**末地门框**记忆 |
| `@forgetmem all` | 作废本存档**全部类型** |
| 其余类型 | `nether_fortress` / `blaze_spawner` / `village` / `ruined_portal` / `warped_forest` / `lava_lake` |

大小写不敏感。生效范围 = **当前 `存档:维度`**；会同步**重写** `knowledge.log`（否则重启复活）。

### 6.3 边界

- 只丢目标 `(存档key, 类型)` 的行，**其余行原样保留**（别的存档 / 别的类型 / 解不出的行都不动）。
- 纯状态写入：**不打断主线**，无任务生命期。
- 与 `@forget`（命名地标）**作用域互不干扰**，两个 token 分开。

---

## 7. 判据修复 —— 要塞记忆旁路眼闸（非命令，代码改动）

**现象**（用户报）：记忆命中会让 bot 直接开挖 / 走向门框，**跳过出发前的眼量检查**；
门若没铺满，会先白跑一趟地牢。

**根因**：判定源用了**语义不等价的输入**。

| 环节 | 事实 | 位置 |
|---|---|---|
| 落盘 | `STRONGHOLD` 记的是**丢眼落点 / 俯冲兜底点**（走廊坐标，**与门框插没插满无关**） | `LocateStrongholdCoordinatesTask.java:326` 与 `:371` |
| 落盘 | `END_PORTAL` 记的才是**亲眼看见的门框位置** | `ExploreStrongholdTask.java:243` |
| 召回 | 原 `recallStrongholdFromKnowledge` = "先查 END_PORTAL 再查 STRONGHOLD"，**不向调用方暴露类型** | `GoToStrongholdPortalTask.java:503`（已删） |
| 门槛 | 召回非 null → `_ranStrongholdLocator=true` → `return getStrongholdPhase` **排在眼闸之前** ⇒ 眼闸被整个旁路 | `BeatMinecraft2Task.java:1733-1741` |

**净损失可证**：`getFilledPortalFrames` 未实测时返回缓存（默认 0）⇒ 不旁路则 `eyesNeededMin = 12 − 0 = 12`，
眼 < 12 正该**先收眼再出发**。

**修法**：该处改用 `recallEndPortalFromKnowledge` —— **只有亲眼见过门框才免检眼量**；
仅走廊记忆仍须过眼闸（过完自然还会走到 `getStrongholdPhase`，其 onTick 的 recall 照旧消费走廊记忆导航）。
时序上眼闸在置位**之前**跑 = 出发前一次性闸，不会触发原注释担心的"定位中被反复打断"。
死代码 `recallStrongholdFromKnowledge` 已删。

> ⚠️ 这条**不需要对端做任何事**，纯模组侧行为修正。

---

## 8. 部署状态

| 项 | 值 |
|---|---|
| commit | `c52b5f4e`（含冒烟测试暴露的两个回执修；前有 `b484451f` / `a52ca1d6` / `714c2edd` / `f1b9d889`） |
| 构建 | `./gradlew --offline build` ✅ BUILD SUCCESSFUL |
| jar | `build/libs/altoclef-0.4.0.jar`，md5 `e5b4494e62514f593b7eabb154b34c4c` |
| 部署 | ✅ **已部署**（三处 mods + `build/libs` 四方 md5 一致；备份 `altoclef-0.4.0.jar.bak_20261004_1149`×3） |

**部署流程**（已完成）：客户端全退 → 备份三处 `mods/altoclef-0.4.0.jar`（`bak_20261003_2319` / `bak_20261003_2321`）
→ 覆盖 → 校验 src 与 deployed md5 一致。三处 mods 目录：
`versions/1.18.2-Fabric-1/mods/`、`versions/1.18.2-Fabric-2/mods/`、根 `.minecraft/mods/`。

**验证**：跑 `python altoclef/bridge_smoke_test.py`（见 `DOC_bridge_commands.md` §8）。

---

## 9. 下次实跑应见判据

1. `/status` 出现 `onlinePlayers`（多人在线时）与 `sleepingPlayers`（有人睡时）。
2. 发 `@quota blaze_rod 0` 后，该 bot 的 `mainlineProgress` 应**不再推进 `烈焰棒N/8`**；
   发 `@quota blaze_rod 3` 后，收集数应**停在 3**（而非 8）。
3. 发 `@sleep wait-dawn` 后，`action_done(completed)` 文本含"熬到天亮"，`activeAction.cmd == "sleep"`；
   **天亮时会再发一条 `action_done(completed)`「已醒来（天亮），已自动回主线」**；
   期间被新指令顶替应变 `cancelled`，被 `@pause_main` 应变 `interrupted`。
4. 发 `@sleep bed-only` 且附近无床时，应**立刻回话拒绝**（`action_done`），
   且**不出现** `CollectBedTask` / `Getting a bed first` 的远征行为。
5. `sleep` / `quota` / `forgetmem` 不应出现在 403 列表里（白名单已含）。
6. 发 `@forgetmem stronghold` 后，`knowledge.log` 里该存档的 `STRONGHOLD` 行应消失，且**重启后不复活**。
7. **仅有 `STRONGHOLD`（走廊）记忆、且背包眼 < 12 时**，bot **不应**直接开挖进地牢 —— 应先收眼再出发
   （修复前会直接开挖，这是本次 §7 修的那条）。日志应见 `[PortalMemory] END_PORTAL knowledge hit` **不出现**，
   而走眼闸分支。
