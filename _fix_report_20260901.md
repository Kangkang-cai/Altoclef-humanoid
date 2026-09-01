# AltoClef 事故恢复与问题修复报告

日期：2026-09-01
项目：d:\Project\life01\altoclef（Minecraft 1.18.2 AI 行为 mod）
测试环境：D:\minecraft.minecraft

***

## 一、本次工作的两大主线

1. **事故抢救：修复事故 AI 反编译造成的源码损坏**（git 无法回滚）
2. **行为修复：解决"掉落肉已进背包仍卡在拾取"的卡死**

***

## 二、事故还原：为什么上个月的 commit / 回滚救不了我们

### 2.1 仓库结构真相

`altoclef/` 是一个**独立的 git 仓库**，但它从未提交过任何自定义改动：

- `git log` 里全是上游官方 commit（最新 `af22e3b`，clone 时的版本）

- 我们积累的 100+ 个自定义修复全部在“工作区未提交”状态

- 根仓库 `d:\Project\life01\.git` 只把 altoclef 当作 `?? untracked` 目录，没跟踪源码

**结论**：事故发生时（事故 AI 把两个源码文件反编译成了 Minecraft 混淆名 `class_2248`/`class_310` 等），git 工作区里“损坏但可运行”的版本从未被 commit，所以 `git checkout`/`revert` 无法回到那个状态——**回滚只会回到官方基线（删光全部自定义），或不动作（保留损坏）**。

### 2.2 损坏的两个文件与症状

| 文件                                           | 损坏内容                                                         |
| -------------------------------------------- | ------------------------------------------------------------ |
| `tasks/CraftGenericWithRecipeBooksTask.java` | 3 个 intermediary token                                       |
| `tasks/resources/MineAndCollectTask.java`    | 11 个类 + 24 个方法 + 49 个字段，全被替换成 `class_*`/`method_*`/`field_*` |

**症状**：编译报 `找不到符号 net.minecraft.class_xxx`，项目完全无法构建。

### 2.3 抢救方案：利用 yarn 映射表逆向恢复（反混淆）

不做 git 回滚（那会丢自定义），而是用本地 Loom 的 `mappings.tiny`（yarn named 命名表）做**精确逆向映射**。

`mappings.tiny` 文件（约 4.5MB）里记录了 `intermediary 名 → yarn named 名` 的完整对应：

- 类：`class_2248` → `net.minecraft.block.Block`、`class_310` → `MinecraftClient`、`class_1799` → `ItemStack`…

- 方法：`method_7909` → `getItem`、`method_10069` → `add`…

- 字段：`field_9486` → `biome`…

我写脚本：

1. 解析 tiny 建立 class/method/field 三张映射表（共 class 6394 / method 30021 / field 28640 条）
2. 对损坏文件做**单词边界精确替换**（`\bclass_2248\b` → `Block`…），避免误伤普通词
3. 手动补 2 处残缺路径（`net.minecraft.BedBlock` → `net.minecraft.block.BedBlock`）和 1 个非唯一方法 `method_10069`(add，BlockPos.add)

**结果**：`BUILD SUCCESSFUL`，代价最低，且**所有已累积的自定义修复逻辑全部保留**（反混淆只改名字，不改逻辑，逐项核验 `shouldCommitToAxeChain`/`itemTargetOwnedIncludingCraftGrid` 等均在）。

### 2.4 教训与补救：建立 checkpoint

修复后立即把已验证状态 commit 成恢复锚点：

```
b898f61 chore: remove stray build_err.txt from checkpoint
793a7f0 checkpoint: 项目自定义修复快照(死亡恢复/床流程/斧子分阶段合成/战斗优化等)
af22e3b (上游)
```

以后任何事故都能 `git checkout -- <file>` 找回已修复版本。**关键教训：每轮关键修复完成后应立即 commit。**

***

## 三、行为修复：掉落肉已进背包仍卡在拾取（Wander for Infinity）

### 3.1 现象（用户实测）

移除掉落实体本身后，任务仍卡在：

```
<Pickup Dropped Items: [[chicken] x 1]> Waiting for calculations I think (wandering)
<Wander for Infinity blocks> Exploring.
```

**肉已经在背包里**，但任务永不结束。

### 3.2 第一轮修复（有限游荡，必要但不充分）

覆盖 `getWanderTask()` 返回持久化的 `TimeoutWanderTask(20)`（20 格有限游荡）。部署后日志确认 `Wander for Infinity` 已变为 `Wander for 20.0 blocks`——**但用户实测仍卡死**。

### 3.3 第二轮排查：真正的根因在任务系统终止语义

17:04:52 测试会话日志（altoclef\_tasks-20260901-170452.log）实锤：17:05:03.725 拾取任务进入游荡后，到 17:05:28 关游戏 **25 秒零任务切换**——游荡子任务走完 20 格也无人释放父任务。卡死链条：

1. [Task.java](file:///d:/Project/life01/altoclef/src/main/java/adris/altoclef/tasksystem/Task.java) `tickInternal` 对子任务**没有 isFinished 检查**——`_sub.tick()` 无条件执行，子任务完成与否的终止权完全在父任务手里（父任务须自行重新评估并停止返回它）
2. `PickupDroppedItemTask`（及 `AbstractDoToClosestObjectTask`）**从未覆写** **`isFinished()`** → 基类默认**恒返回 false**
3. 粘性持有者 [CollectFoodTask.java:511](file:///d:/Project/life01/altoclef/src/main/java/adris/altoclef/tasks/resources/CollectFoodTask.java#L511) 判 `if (_foodDropPickupTask.isActive() && !_foodDropPickupTask.isFinished(mod)) return it;` → 恒真 → **永不放手** → baritone 无限探索

即：游荡子任务"可完成"没有意义，因为**没有人查询拾取任务本身的完成状态**，而它永远是 false。

### 3.4 最终修复：给 PickupDroppedItemTask 补上 isFinished 语义

```java
@Override
public boolean isFinished(AltoClef mod) {
    if (_currentDrop != null && isValid(mod, _currentDrop)) {
        return false;
    }
    return getClosestTo(mod, getOriginPos(mod)).isEmpty();
}
```

语义："正在追的掉落物仍有效，或附近还能找到匹配掉落物 → 未完成；没有任何可追目标（已拾取/despawn/全拉黑）→ 完成"。

- `_currentDrop` 优先判定：tracker 感知记忆（20s）过期后 `getClosestTo` 会漏掉远处仍在追的实体，不能因此误判完成

- `getClosestTo` = tracker 缓存（有 ensureUpdated 每 tick 刷新）+ 世界实体直接扫描双通道，目标真消失时必然返回空

### 3.5 影响面核验（全部 5 个字段持有方）

| 持有方                                    | 现状                                           | 影响   |
| -------------------------------------- | -------------------------------------------- | ---- |
| CollectFoodTask.\_foodDropPickupTask   | **本次卡死点**                                    | 修复   |
| CollectWoolTask.\_woolDropPickupTask   | 同构粘性 bug（潜伏）                                 | 一并修复 |
| CollectCropTask.\_collectSeedTask      | 同构粘性（L88）                                    | 一并修复 |
| ResourceTask.\_pickupTask (L170)       | 有 itemDropped 门控自愈，但 `!isFinished` 恒真导致超范围也粘 | 改善   |
| MineOrCollectTask.\_pickupTask (L1201) | 只在 getGoalTask 返回，不查 isFinished              | 无影响  |

每 tick 新建式调用方（BeatMinecraft2Task 等 20+ 处）不查 isFinished，不受影响。

### 3.6 验证结果

- `BUILD SUCCESSFUL`（5s，--no-build-cache --rerun-tasks）

- jar `altoclef-0.4.0.jar`（17:25:21，3.68MB）已覆盖游戏目录 `D:\minecraft\.minecraft\mods\` 和暂存副本 `_altoclef_assets\mods\`

- 预期表现：拾取任务在目标消失后 1 tick 内被父任务释放（任务日志出现 pickup STOP + 后续任务 SUB），不再出现"拾取任务 + 游荡"长时间静默；`Collect 120 food` 正常推进

***

## 三C、地表误判"发现矿洞"修复：MineDownExploreTask 增加有顶覆盖判定

### 3C.1 现象（用户实测）

bot 在**陆地表面**（刚砍完树），主线挖铁 `MineDownExploreTask` 一启动就"发现矿洞"切入 Biased 探索，且被拉到**海面**（y=63）上去探索矿洞，反复横跳，最终被 ender eye 链打断。

### 3C.2 根因：`isCaveish` 的普通 AIR 分支把地表当洞

[findNearbyCave](file:///d:/Project/life01/altoclef/src/main/java/adris/altoclef/tasks/movement/MineDownExploreTask.java) 里 `isCaveish = (b == CAVE_AIR) || (b == AIR && inOpenCave)`，本意是 8-31 识别繁茂洞穴（普通 air）。但 `inOpenCave = 连通空气≥40` 在地表恒真（地表连通空气无限大）→ **地表开阔地空气全被当成"洞空气"** → 站在地表第一步就误判。

日志实锤（altoclef\_diag.log）：`19:32:32 found cave at -172,69,924`（玩家在 -177,76,929 地表）→ bot 被拉到 `-257,63,891`（y=63 海平面）。

### 3C.3 修复：普通 air 算洞须有顶覆盖

洞的本质是**被岩壁包围的有顶空腔**。新增 `hasOverheadCover()`：候选 air 块向上扫 24 格，遇到实心方块 → 有顶算洞；全是空气/流体/树冠 → 露天不算洞。

- 繁茂洞穴空气上方是岩石顶 → 仍识别 ✓（保留 8-31 需求）

- 地表开阔地空气上方是天空 → 不再误判 ✓

- 树冠（树叶/原木 PillarBlock）不算岩顶 → 砍树场景不误判 ✓

- CAVE\_AIR 分支（普通洞穴）完全不受影响 ✓

### 3C.4 验证结果

- `BUILD SUCCESSFUL`（4s，--no-build-cache --rerun-tasks）

- jar `altoclef-0.4.0.jar`（19:38:51，3.68MB）已覆盖游戏目录和暂存副本

- 预期表现：地表挖矿时老老实实挖楼梯下矿，不再误判"发现洞"、不再被拉到海边；真正进入岩层挖穿进洞才触发矿洞探索；繁茂洞穴仍可识别

***

## 四、附：本会话早前处理的其他修复（保留确认）

反混淆恢复后逐项核验这些逻辑仍在（均为此前会话累积、非本次新增）：

| 文件                      | 修复                                                                                                                   |
| ----------------------- | -------------------------------------------------------------------------------------------------------------------- |
| MineAndCollectTask.java | `shouldCommitToAxeChain` + `computeNextAxeTask`：砍树前做斧子分阶段调度（石斧→做台→木斧→撸木兜底），原 effectivePlanks≥8 就 COMMIT 木斧却没工作台的分支死锁 |
| StorageHelper.java      | `itemTargetOwnedIncludingCraftGrid` 数量 BUG：原来只要 1 件就判达标（忽略 targetCount），修复为累计匹配物品计数 ≥ 目标数                            |
| CraftInTableTask.java   | 材料检查用 `hasRecipeMaterialsOrTarget` 单一闸门（移除此前"木板当量"有害旁路）                                                              |
| CollectFoodTask.java    | `calculateFoodPotential` 用真实背包判定食物达标，杜绝 tracker 缓存陈旧导致的反复拾取                                                          |

***

## 五、关键经验（写进 project\_memory）

1. **altoclef 是独立 git 仓库，且从未提交自定义改动** —— 回滚只能到官方基线。**必须每轮修复后 commit**。
2. 事故导致 intermediary 混淆源码时，**用** **`mappings.tiny`** **逆向映射反混淆**比 git 回滚更安全（保住全部自定义）。
3. `AbstractDoToClosestObjectTask` 在"找不到目标"时默认走**无穷游荡**，任何继承它的 task（如拾取）都应在"目标消失"时用**有限游荡**兜底，否则永不结束。
4. 游荡兜底必须返回**持久复用的 task 实例**，不能每 tick new，否则 `_origin` 反复重置导致游荡永不完结。
5. **任务系统终止语义**：`Task.tickInternal` 不检查子任务 isFinished——子任务完成不会自动终止，父任务必须自行重估并停止返回它。因此**任何被父任务"粘性持有"的子任务都必须正确覆写 isFinished**（基类默认恒 false = 永不放手）。有限游荡只是让游荡"可完成"，若持有者不查询完成状态仍是死路。
6. 排查"任务静默卡死"：看任务日志长时间零 SUB/STOP 切换的时间段 = 某个父任务粘住了永不完成的子任务，优先查该子任务链上哪个 isFinished 恒 false。

