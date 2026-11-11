# AltoClef Humanoid（拟人化 Minecraft 通关机器人）

一个**拟人化 Minecraft 自动通关机器人** —— 基于 [AltoClef](https://github.com/gaucho-matrero/altoclef)（寻路由 [Baritone](https://github.com/cabaletta/baritone) 驱动）的增强分支。它以**拟人的感知方式**游玩：不开透视、不作弊感知，只知道自己亲眼看到、能记住的东西；像人一样探索，像人一样观察和移动。

[English (README.md)](README.md)

**目标版本：** Minecraft **1.18.2**（Fabric，客户端 mod）

## 这是什么？

[AltoClef](https://github.com/gaucho-matrero/altoclef) 是 2021 年 5 月[第一个完全自主通关 Minecraft 的机器人](https://youtu.be/baAa6s8tahA)。但和大多数 Minecraft 机器人一样，它在感知上是作弊的：它能"知道"墙后和整个加载范围内的一切。本分支的主线工作就是拿掉这份不公平优势，让 bot 真正**像人一样玩游戏**——并沉淀了数百项来自真实长时间无人值守游玩的健壮性修复。

## 拟人化在哪里

### 1. 不透视：感知只限于亲眼看到过的东西

bot 保留一份拟人风格的**经验记忆**（`KnowledgeMemory`）：只记住自己**亲眼见过**（经视线验证）的结构，并据此推断附近可能有资源——就像玩家瞥见村庄一角就会猜测附近有箱子。关键在于：这些推断一律标注为**猜测而非观测**（由内置录制系统强制），bot 永远不会基于它不可能合法获得的信息行动。

### 2. 拟人化探索策略

不用全知式搜索扫地图，而是**带方向偏置的拟人化探索**（`BiasedExploreTask`）：带方向门的远征、朝向粘滞、记住已搜过的区域、像玩家一样放弃死路、拿到目标就收队。

### 3. 拟人化表现

**拟人视线调度器**（`HumanoidGaze` + `SmoothLookController`）：相机大部分时间平视扫视周围环境，不盯脚也不盯天，时不时看向附近的玩家/bot/生物并平滑跟随，帧级平滑转头而非机器人式瞬间甩头。玩家发光描边可配置（可排除操作者本人）。bot 还会**像人一样睡觉**（夜间在服务器上与其他玩家同步入睡）。

### 4. 长时间会话的生存健壮性

死亡恢复链、岩浆逃生（垫塔）机动、下界传送门重燃与到场出闸处理、卡死任务软重启守卫、食物远征、熔炉熔炼链、背包/丢垃圾策略——这些是让它能无人值守连续游玩数小时的修复。

### 5. 实验性：多 bot 协作与 LLM 导演桥（边缘设施，不完善）

另有 HTTP `/status` 端点 + 聊天/桥接指令协议（`@follow`、`@give`…）与跨 bot 坐标共享，用于多 bot 协作和外部 AI 导演指挥。**这部分是边缘设施，尚未打磨**——本分支的重心是上面的拟人化行为。想深究可读 [DOC_bridge_commands.md](DOC_bridge_commands.md)。

## 构建

```bash
./gradlew build
# 产物：build/libs/altoclef-*.jar
```

需要 JDK 17（项目基于 Java 17 / Minecraft 1.18.2）。

## 安装

1. 把构建出的 `altoclef-*.jar` 放进 `.minecraft/mods/`（版本隔离目录同样适用，如 `versions/1.18.2-Fabric/mods/`）。
2. 从 [Baritone releases](https://github.com/cabaletta/baritone/releases) 下载 Baritone **fabric 1.18.2** 版（`baritone-unoptimized-fabric-1.18.2.jar`）放进同一目录。
3. 如果以前装过 Baritone，先删掉旧的 Baritone 配置文件 —— 遗留配置会干扰 AltoClef。

## 使用

- 单 bot：聊天指令以 `@` 开头 —— 见[使用指南](usage.md)（`@gamer` 可完全自主通关，`@get`、`@food`、`@equip` 等）。
- 多 bot / AI 导演模式：阅读 [DOC_bridge_commands.md](DOC_bridge_commands.md) —— 包含 `/status` JSON 结构、跟随/职责生命周期、指令回执格式。

## 文档

| 文档 | 内容 |
|---|---|
| [usage.md](usage.md) | 单 bot 聊天指令 |
| [DOC_bridge_commands.md](DOC_bridge_commands.md) | 多 bot 桥接：`/status` 结构、指令协议、回执 |
| [RECORDING.md](RECORDING.md) | 内置行为录制（调试用） |
| [develop.md](develop.md) | 开发环境搭建（IntelliJ、JDK） |
| [AltoClef wiki](https://github.com/gaucho-matrero/altoclef/wiki/1:-Documentation:-Big-Picture) | 上游架构指南（任务链、Baritone 集成） |

## 致谢与许可证

- 上游项目：[AltoClef](https://github.com/gaucho-matrero/altoclef)，作者 Adris Jautakas（gaucho-matrero）— MIT 许可证。
- 寻路与世界交互：[Baritone](https://github.com/cabaletta/baritone) — GNU LGPL-3.0。
- 本分支：MIT 许可证（见 [LICENSE](LICENSE)），保留上游版权声明。
