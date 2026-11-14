# AltoClef Humanoid

A **human-like Minecraft bot** that autonomously plays and beats the game — an enhanced fork of [AltoClef](https://github.com/gaucho-matrero/altoclef) (powered by [Baritone](https://github.com/cabaletta/baritone)). It plays with **human-like perception**: no x-ray, no wall-hacks, no radar — the bot only knows what a player could see and remember, explores like a human would, and looks and moves like a player.

[中文文档（README.zh-CN.md）](README.zh-CN.md)

**Target:** Minecraft **1.18.2** (Fabric, client-side mod)

**Links:** [Modrinth](https://modrinth.com/mod/altoclef-humanoid) · [Intro article (Chinese)](https://zhuanlan.zhihu.com/p/2104170161095828482) · [CSDN (Chinese)](https://blog.csdn.net/2401_87579346/article/details/170602519)

## What is this?

[AltoClef](https://github.com/gaucho-matrero/altoclef) became [the first bot to beat Minecraft fully autonomously](https://youtu.be/baAa6s8tahA) (May 2021). Like most Minecraft bots, though, it cheats on perception: it "knows" what is behind walls and everywhere in the loaded world. This fork's main line of work is removing that unfair advantage and making the bot actually play **like a human** — plus hundreds of robustness fixes accumulated from real, long-running unattended play sessions.

## What's human-like about it

### 1. No x-ray: perception limited to what the bot has actually seen

The bot keeps a human-style **experience memory** (`KnowledgeMemory`): it only remembers structures it has **personally seen** (visibility-filtered), and infers candidate resource areas near them — the way a player who spots the corner of a village assumes chests are nearby. Critically, those inferences are labeled as **guesses, never observations** (enforced by the built-in recording system), so the bot never acts on information it couldn't legitimately have.

### 2. Human-like exploration strategies

Instead of sweeping the world with omniscient searches, the bot uses **biased, human-style exploration** (`BiasedExploreTask`): direction-gated expeditions with bearing stickiness, remembering where it has already searched, giving up dead ends the way a player would, and turning back once it has what it came for.

### 3. Human-like presentation

A **humanoid gaze scheduler** (`HumanoidGaze` + `SmoothLookController`): the camera mostly scans the surroundings at eye level, never stares at its feet or the sky, occasionally looks at nearby players/bots/mobs and smoothly follows them, with frame-level smoothing instead of instant robotic snaps. Optional player glow highlights are configurable (and excluded for the operator). The bot also **mirrors human sleep** (sleeps at night like other players on a server).

### 4. Long-session survival robustness

Death recovery chain, lava-escape (pillar-up) maneuver, nether portal relight & portal-exit handling, stuck-task soft-restart guard, food expeditions, furnace smelting chain, and inventory/trash-dropping policies — the fixes that let it run unattended for hours.

### 5. Experimental: multi-bot & LLM director bridge (edge facility, not polished)

There is also an HTTP `/status` endpoint plus a chat/bridge command protocol (`@follow`, `@give`, ...) with cross-bot coordinate sharing, built to let multiple bots cooperate and be driven by an external AI director. **This part is a peripheral facility and is not polished** — the human-like behavior above is the focus of this fork. See [DOC_bridge_commands.md](DOC_bridge_commands.md) if you want to dig in anyway.

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
