# AltoClef Humanoid

A **human-like Minecraft bot** that autonomously plays and beats the game — an enhanced fork of [AltoClef](https://github.com/gaucho-matrero/altoclef) (powered by [Baritone](https://github.com/cabaletta/baritone)) with **multi-bot coordination** and a **command bridge designed for LLM/AI directors**.

[中文文档（README.zh-CN.md）](README.zh-CN.md)

**Target:** Minecraft **1.18.2** (Fabric, client-side mod)

## What is this?

[AltoClef](https://github.com/gaucho-matrero/altoclef) became [the first bot to beat Minecraft fully autonomously](https://youtu.be/baAa6s8tahA) (May 2021). This fork continues from that baseline and turns it into a **long-running, unattended multi-bot survival system** that can be directed by an external AI (e.g. an LLM "director" agent) over an HTTP bridge and in-game chat commands — with hundreds of robustness fixes accumulated from real, continuous play sessions.

## Key features (this fork vs upstream)

| Area | What's added |
|---|---|
| **Multi-bot coordination** | Leader + follower sub-bot architecture: duty system (`PREP` / `FOLLOW` / `COLLECT` / `SLEEP`), cross-bot coordinate sharing, follow-relationship governance to prevent two bots following each other in a deadlock |
| **AI/LLM command bridge** | HTTP `/status` JSON endpoint (task state, inventory, nearby players, digests) + a structured command protocol (`@follow`, `@give`, `@equip`, `@quota`, ...) with machine-readable receipts and fuzzy item-name correction — designed so an external AI director can reliably drive the bot |
| **Human-like presentation** | Humanoid gaze scheduler (smooth head movement, looks at nearby entities/players instead of staring at feet or sky), configurable player glow highlights |
| **Survival robustness** | Death recovery chain, lava-escape (pillar-up) maneuver, nether portal relight & portal-exit handling, stuck-task soft-restart guard, food expeditions, furnace smelting chain, trash-dropping / inventory management policies |

See [DOC_bridge_commands.md](DOC_bridge_commands.md) for the full bridge command protocol, and [RECORDING.md](RECORDING.md) for the built-in input/behavior recording system used for debugging bot behavior.

## Build

```bash
./gradlew build
# output: build/libs/altoclef-*.jar
```

Requires JDK 17 (the project targets Java 17 / Minecraft 1.18.2).

## Install

1. Copy the built `altoclef-*.jar` into your `.minecraft/mods/` folder (works for isolated version folders too, e.g. `versions/1.18.2-Fabric/mods/`).
2. Copy a Baritone **fabric 1.18.2** jar (`baritone-unoptimized-fabric-1.18.2.jar`) from the [Baritone releases](https://github.com/cabaletta/baritone/releases) into the same folder.
3. If you have old Baritone config files, move/delete them first — preexisting configs can interfere with AltoClef.

## Usage

- Single bot: chat commands prefixed with `@` — see the [Usage Guide](usage.md) (`@gamer` beats the whole game by itself, `@get`, `@food`, `@equip`, ...).
- Multi-bot / AI director mode: read [DOC_bridge_commands.md](DOC_bridge_commands.md) — it documents the `/status` JSON schema, the follow/duty lifecycle, and the exact command receipt format.

## Documentation

| Doc | Content |
|---|---|
| [usage.md](usage.md) | Single-bot chat commands |
| [DOC_bridge_commands.md](DOC_bridge_commands.md) | Multi-bot bridge: `/status` schema, command protocol, receipts |
| [RECORDING.md](RECORDING.md) | Built-in behavior recording for debugging |
| [develop.md](develop.md) | Development setup (IntelliJ, JDK) |
| [AltoClef wiki](https://github.com/gaucho-matrero/altoclef/wiki/1:-Documentation:-Big-Picture) | Upstream architecture guide (task chains, Baritone integration) |

## Credits & License

- Upstream project: [AltoClef](https://github.com/gaucho-matrero/altoclef) by Adris Jautakas (gaucho-matrero) — MIT License.
- Pathfinding & world interaction: [Baritone](https://github.com/cabaletta/baritone) — GNU LGPL-3.0.
- This fork: MIT License (see [LICENSE](LICENSE)); upstream copyright notice retained.
