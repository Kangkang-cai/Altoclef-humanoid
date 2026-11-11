# AltoClef Humanoid（拟人化 Minecraft 通关机器人）

一个**拟人化 Minecraft 自动通关机器人** —— 基于 [AltoClef](https://github.com/gaucho-matrero/altoclef)（寻路由 [Baritone](https://github.com/cabaletta/baritone) 驱动）的增强分支，新增**多 bot 协作**与**面向 LLM/AI 导演的指令桥**。

[English (README.md)](README.md)

**目标版本：** Minecraft **1.18.2**（Fabric，客户端 mod）

## 这是什么？

[AltoClef](https://github.com/gaucho-matrero/altoclef) 是 2021 年 5 月[第一个完全自主通关 Minecraft 的机器人](https://youtu.be/baAa6s8tahA)。本分支在该基线上继续演进，把它改造成一个**可长期无人值守运行的多 bot 生存系统**：外部 AI（例如 LLM「导演」智能体）可以通过 HTTP 桥接接口和游戏内聊天指令指挥 bot 行动，并从大量真实连续游玩会话中沉淀了数百项健壮性修复。

## 本分支相对上游的增强

| 领域 | 新增内容 |
|---|---|
| **多 bot 协作** | 主 bot + 次 bot 架构：职责系统（`PREP` 备料 / `FOLLOW` 跟随 / `COLLECT` 自主收集 / `SLEEP` 睡眠镜像）、跨 bot 坐标共享、跟随关系治理（防止两台 bot 互跟死锁） |
| **AI/LLM 指令桥** | HTTP `/status` JSON 端点（任务状态、背包、附近玩家、场景摘要）+ 结构化指令协议（`@follow`、`@give`、`@equip`、`@quota`…），带机器可读回执与物品名模糊纠错 —— 专为外部 AI 导演可靠指挥而设计 |
| **拟人化表现** | 拟人视线调度器（头部平滑转动、会看向附近实体/玩家而非盯脚盯天）、可配置的玩家发光描边 |
| **生存健壮性** | 死亡恢复链、岩浆逃生（垫塔）机动、下界传送门重燃与到场出闸处理、卡死任务软重启守卫、食物远征、熔炉熔炼链、丢垃圾/背包管理策略 |

完整桥接指令协议见 [DOC_bridge_commands.md](DOC_bridge_commands.md)；内置的行为录制系统（用于调试 bot 行为）见 [RECORDING.md](RECORDING.md)。

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
