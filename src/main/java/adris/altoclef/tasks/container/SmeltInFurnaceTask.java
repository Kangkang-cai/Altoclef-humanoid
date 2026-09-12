package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask.DoSmeltInFurnaceTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask.FurnaceCache;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.resources.CollectFuelTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.slot.*;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.FurnaceSlot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.util.slots.Slot;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;


// Ref
// https://minecraft.gamepedia.com/Smelting

/**
 * Smelt in a furnace, placing a furnace and collecting fuel as needed.
 */
public class SmeltInFurnaceTask extends ResourceTask {

    private final SmeltTarget[] _targets;

    private final DoSmeltInFurnaceTask _doTask;

    // (2026-09-10 收尾活锁看门狗) 15:13 场次实锤: 烧完铁后 Receiving Output
    // ↔ Moving Materials(x0) ↔ Filling fuel ↔ Guarding furnace 每 6s 一轮活锁
    // 70s+ (背包满收不走产物 / 炉子点不开等, 任何原因都没有出口)。isFinished
    // 要求 targetsMet && isFurnaceDrained, 炉排不空就永不结束 → 无限等。
    // 看门狗口径: 只统计熔炉界面打开的 game tick (去程取燃料/守卫驻留不计),
    // 槽位快照 (输出/材料/燃料/背包目标数) 有任何变化即视为有进展归零;
    // 零进展连续 1200 tick (60 游戏秒) → 放弃: 记 [SmeltGiveUp] 快照日志、
    // 关屏、置触发层 static 冷却 30s (实例每轮会被父级重建, 实例层挡不住)。
    private static final int SMELT_STALL_GIVEUP_TICKS = 20 * 60;
    private static final long SMELT_GIVEUP_COOLDOWN_MS = 30_000;
    private static long _staticGaveUpUntilMs = 0;
    private boolean _gaveUp = false;
    private String _stallSnap = null;
    private int _stallOpenTicks = 0;

    public SmeltInFurnaceTask(SmeltTarget[] targets) {
        super(extractItemTargets(targets));
        _targets = targets;
        // TODO: Do them in order.
        _doTask = new DoSmeltInFurnaceTask(targets[0]);
    }

    public SmeltInFurnaceTask(SmeltTarget target) {
        this(new SmeltTarget[]{target});
    }

    private static ItemTarget[] extractItemTargets(SmeltTarget[] recipeTargets) {
        List<ItemTarget> result = new ArrayList<>(recipeTargets.length);
        for (SmeltTarget target : recipeTargets) {
            result.add(target.getItem());
        }
        return result.toArray(ItemTarget[]::new);
    }

    public void ignoreMaterials() {
        _doTask.ignoreMaterials();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        if (_targets.length != 1) {
            Debug.logWarning("Tried smelting multiple targets, only one target is supported at a time!");
        }
        mod.getBehaviour().push();
        mod.getBehaviour().markSlotAsConversionSlot(FurnaceSlot.INPUT_SLOT_MATERIALS, stack -> {
            // Eventually do multiple targets
            return _targets[0].getMaterial().matches(stack.getItem());
        });
        mod.getBehaviour().markSlotAsConversionSlot(FurnaceSlot.INPUT_SLOT_FUEL, stack -> ItemHelper.isFuel(stack.getItem()));
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // (2026-08-24 移除围蔽) 用户实测"烧熔炉时围住自己"的机制不好用, 已去掉。
        // 熔炉放置/打开走 _doTask (DoStuffInContainerTask 自带放置逻辑)。
        //
        // (2026-08-27 回退) 熔炉回收始终败给 gear(做工具) 对烧炼子任务的顶替,
        // 无法在烧炼内可靠执行 → 改回"烧完排空即结束" (isFinished 依赖
        // isFurnaceDrained, 保证熔炉内不残留未烧玩/未取的东西)。回收交给
        // BeatMinecraft 的"熔炉即收"站桩分支尽力而为。
        // (2026-09-10 看门狗) 已放弃的本实例停摆; 冷却期内新建的实例不启动
        // 烧炼, 原地等待 30s 后再试 — 防止父级立刻重建又原样活锁。
        if (_gaveUp) return null;
        if (System.currentTimeMillis() < _staticGaveUpUntilMs) {
            setDebugState("Smelt give-up cooldown");
            return null;
        }
        // 看门狗主体: 只统计熔炉界面打开的 tick (去程取燃料/守卫驻留期界面
        // 关着, 不计时, 不误伤合法的长时间取料)。
        if (mod.getPlayer() != null
                && mod.getPlayer().currentScreenHandler instanceof FurnaceScreenHandler) {
            ItemStack out = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
            ItemStack mat = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
            ItemStack fuel = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);
            int invTarget = 0;
            for (ItemTarget t : _itemTargets) {
                invTarget += mod.getItemStorage().getItemCountInventoryOnly(t.getMatches());
            }
            String snap = out.getCount() + "/" + mat.getCount() + "/" + fuel.getCount() + "/" + invTarget;
            if (!snap.equals(_stallSnap)) {
                _stallSnap = snap;
                _stallOpenTicks = 0;
            } else {
                _stallOpenTicks++;
            }
            if (_stallOpenTicks >= SMELT_STALL_GIVEUP_TICKS) {
                giveUp(mod, out, mat, fuel, invTarget);
                return null;
            }
        }
        return _doTask;
    }

    /**
     * (2026-09-10) 活锁看门狗触发: 放弃本实例 (isFinished 短路放行, 炉内遗留
     * 现状保留不强行腾), 记快照日志定案现场, 触发层 static 冷却。
     */
    private void giveUp(AltoClef mod, ItemStack out, ItemStack mat, ItemStack fuel, int invTarget) {
        _gaveUp = true;
        _staticGaveUpUntilMs = System.currentTimeMillis() + SMELT_GIVEUP_COOLDOWN_MS;
        String msg = "[SmeltGiveUp]\t零进展 " + (SMELT_STALL_GIVEUP_TICKS / 20)
                + "s(界面开启口径) 放弃烧炼"
                + " output=" + out.getCount() + " material=" + mat.getCount()
                + " fuel=" + fuel.getCount() + " invTarget=" + invTarget
                + " cache=" + describeFurnaceCache();
        Debug.logMessage(msg);
        Debug.logToFile("altoclef_diag.log", msg);
        // 关屏 (MC 自动把光标物品弹回背包/掉落), 遗留物保持现状 — 放弃语义。
        StorageHelper.closeScreen();
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        // (2026-09-06 烧肉被抢断止损) 用户实测: gear 任务顶替 Smelting 时
        // (16:27:26 "Smelting raw food: chicken" Waiting 期被 Get catalogued
        // 抢断), 熔炉输出槽 cooked_mutton x14 没收走就 STOP, 全部遗弃在炉里。
        // onStop 同步执行、不能派子任务, 这里尽力而为: 熔炉界面还开着 →
        // 直接 QUICK_MOVE 点一次输出槽 (一次点击搬走整组), 再关屏。
        // 失败分支全部退化为现状 (物品留在炉里), 不新增丢物品路径:
        // - 界面没开 (被打断时还在路上) → 收不了, 跳过;
        // - 背包满/放不下 → QUICK_MOVE 无效, 跳过 (不在 onStop 里腾位/丢垃圾,
        //   否则会拆掉 2026-09-03 满包死锁修复的链路);
        // - canDoSlotAction 冷却中 → 本 tick 点不了, 跳过。
        // 注: 只动本任务类, 不泛化到 DoStuffInContainerTask 基类 (箱子/工作台
        // 语义不同)。
        try {
            if (mod.getPlayer() != null && mod.getWorld() != null
                    && mod.getPlayer().currentScreenHandler instanceof FurnaceScreenHandler) {
                ItemStack output = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
                if (!output.isEmpty()) {
                    if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(output, false).isPresent()
                            && mod.getSlotHandler().canDoSlotAction()) {
                        mod.getSlotHandler().clickSlot(FurnaceSlot.OUTPUT_SLOT, 0, SlotActionType.QUICK_MOVE);
                        Debug.logMessage("Furnace stop-loss: QUICK_MOVE output " + output + " before interrupt by "
                                + interruptTask.getClass().getSimpleName() + ")");
                    } else {
                        Debug.logMessage("Furnace stop-loss skipped: no inventory room or click cooldown, output "
                                + output + " stays in furnace");
                    }
                }
            }
        } catch (Exception e) {
            Debug.logWarning("Furnace stop-loss failed (ignored): " + e);
        }
        // Close furnace screen
        StorageHelper.closeScreen();
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (2026-09-10 活锁看门狗) 放弃语义: 看门狗触发后视为完成放行, 炉内
        // 遗留现状 (日志 [SmeltGiveUp] 已记快照), 由上层自行改道 (挖矿/换炉)。
        if (_gaveUp) return true;
        // 一次烧满原则：只有数量达标 且 熔炉已排空（材料烧完、产物取出）
        // 才算结束——否则塞进去的材料就浪费在炉子里了。
        // (2026-08-22 收铁卡死修复) super.isFinished (ResourceTask) 用
        // itemTargetsMetInventoryNoCursor 判定——熔炉输出槽收铁时铁锭
        // 先进光标再落袋，若按 NoCursor 算则收铁后仍"未达标"，任务永不
        // 结束，反复收铁 + 光标滞留卡死（用户实测：烧完铁卡熔炉界面
        // 3 分 44 秒，介入才恢复）。光标里的铁已实际拿到，应计入达标。
        return (StorageHelper.itemTargetsMetInventory(mod, _itemTargets) || _doTask.isFinished(mod)) && _doTask.isFurnaceDrained();
    }

    /**
     * (2026-09-08 烧肉 ping-pong 修复) 对外暴露"炉已排空"查询: 供上层
     * (BeatMinecraft2Task 烧肉分支) 在丢弃缓存实例前判断炉里是否还有
     * 料/产物——防止"肉已塞进炉、背包为 0"时被误判弃缓存。
     */
    public boolean isFurnaceDrained() {
        return _doTask.isFurnaceDrained();
    }

    /**
     * (2026-09-08 诊断) 缓存快照文本, 供上层弃缓存/保活日志携带现场。
     */
    public String describeFurnaceCache() {
        return _doTask.describeFurnaceCache();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof SmeltInFurnaceTask task) {
            return task._doTask.isEqual(_doTask);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return _doTask.toDebugString();
    }

    public SmeltTarget[] getTargets() {
        return _targets;
    }

    @SuppressWarnings("ConditionCoveredByFurtherCondition")
    static class DoSmeltInFurnaceTask extends DoStuffInContainerTask {

        private final SmeltTarget _target;
        private boolean _ignoreMaterials;

        private FurnaceCache _furnaceCache = new FurnaceCache();

        // (2026-09-07 熔炉守卫) 烧制等待期不抱界面: 走 FurnaceSmeltGuardTask
        // (关屏守炉+扫视+16格内派杀), 驻留期满由本类 onTick 恢复 super.onTick
        // 走 walk&reopen 重开炉收货。守卫接管期间必须压住父类 onTick —— 否则
        // 父类每 tick 重判 isContainerOpen=false 会立刻切回 walk&open, 守卫活
        // 不过 1 tick。
        private FurnaceSmeltGuardTask _guard = null;
        private boolean _guardActive = false;

        private final ItemTarget _allMaterials;

        // (2026-08-22 烧铁无煤横跳修复) 燃料收集"提交期"计时器。CollectFuelTask
        // 降级用 2x2 合成格做木板时, StorageHelper.calculateInventoryFuelCount
        // 会把合成格/光标里的木板也计为燃料 (1.5 单位/块) → 燃料计数瞬时虚高
        // → 下一 tick 燃料检查误通过 → 掉进 super.onTick 的"合成/放置熔炉"
        // 分支 (矿洞内 nearest.isEmpty() 恒真, 每 tick 都发), 反向打断木板
        // 合成 → Get Fuel ↔ Get container item 高频横跳 (日志 20:31:15~37
        // TaskPingPong, 用户实测烧铁无煤时任务不断切换)。
        // 提交期: 进入 Get Fuel 后 5 秒内持续返回 CollectFuelTask, 让木板
        // 真正落袋再评估容器; 燃料真够 (CollectFuelTask.isFinished) 或超时
        // 解除提交。
        private final TimerGame _fuelCommitTimer = new TimerGame(5);
        private boolean _fuelCommitted = false;

        public DoSmeltInFurnaceTask(SmeltTarget target) {
            super(Blocks.FURNACE, new ItemTarget(Items.FURNACE));
            _target = target;
            _allMaterials = new ItemTarget(Stream.concat(Arrays.stream(_target.getMaterial().getMatches()), Arrays.stream(_target.getOptionalMaterials())).toArray(Item[]::new), _target.getMaterial().getTargetCount());
        }

        public void ignoreMaterials() {
            _ignoreMaterials = true;
        }

        /**
         * (2026-08-23 围蔽前置) 材料与燃料是否已就绪 — 矿洞围自己的
         * 触发条件之一 (见外层 onResourceTick 注释)。炉未打开时
         * _furnaceCache 为空, 按"背包产物抵扣"的保守口径计算, 与
         * onTick 需求公式在缓存空时一致。
         */
        public boolean isReadyToSmelt(AltoClef mod) {
            ItemTarget materialTarget = _allMaterials;
            ItemTarget outputTarget = _target.getItem();
            int outInInventory = mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches());
            int materialsNeeded = materialTarget.getTargetCount() - outInInventory;
            if (materialsNeeded > 0
                    && mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) < materialsNeeded) {
                return false;
            }
            double fuelNeeded = Math.max(0, materialTarget.getTargetCount() - outInInventory);
            return StorageHelper.calculateInventoryFuelCount(mod) >= fuelNeeded;
        }

        /**
         * 一次烧满原则：熔炉是否已排空（材料槽无剩余目标材料、输出槽无
         * 剩余产物）。缓存只在打开熔炉时刷新，若缓存显示还有剩余，任务
         * 不会结束并会走回熔炉取货/等待烧完。
         * <p>
         * (2026-09-08 记得管理修复) 新增 _cacheKnown 闸门: **从未观察到任何
         * 熔炉时不算已排空**。旧版空缓存=drained=true, 上层"生肉消失+排空"
         * 就弃缓存换肉 —— 17:12 实测: 牛肉x11进炉, 熔炉方块随后被毁, 任务
         * 转去做新炉并打开了空炉, 缓存被空炉数据覆盖 → "已排空" → 弃缓存
         * → 牛肉x8(掉落物)永远遗忘。未知≠排空, 未知必须保守处理。
         */
        public boolean isFurnaceDrained() {
            boolean materialLeft = _target.getMaterial().matches(_furnaceCache.materialSlot.getItem())
                    && !_furnaceCache.materialSlot.isEmpty();
            boolean outputLeft = _target.getItem().matches(_furnaceCache.outputSlot.getItem())
                    && !_furnaceCache.outputSlot.isEmpty();
            boolean drained = _cacheKnown && !materialLeft && !outputLeft;
            // (2026-09-08 诊断) 翻转才记, 不刷屏。
            if (drained != _lastDrainedValue) {
                boolean screenOpen = net.minecraft.client.MinecraftClient.getInstance().player != null
                        && net.minecraft.client.MinecraftClient.getInstance().player.currentScreenHandler instanceof FurnaceScreenHandler;
                Debug.logToFile("altoclef_diag.log", "[SmeltDrained]\t" + _lastDrainedValue + "->" + drained
                        + " target=" + _target.getMaterial() + " cache=" + describeFurnaceCache()
                        + " known=" + _cacheKnown + " screenOpen=" + screenOpen);
                _lastDrainedValue = drained;
            }
            return drained;
        }

        private boolean _lastDrainedValue = true;
        // (2026-09-08 记得管理) 缓存所属熔炉的坐标; null=还没见过任何炉子。
        private BlockPos _cachePos = null;
        // 是否已实际观察到炉内数据 (打开过熔炉且坐标未切换)。
        private boolean _cacheKnown = false;

        // (2026-09-12 界面闪烁修复) 开屏 latch —— 界面刚打开后的最短显示时长。
        // 现场 20260912-085838 (用户"熔炉界面一闪一闪地开合"): bot 走到炉前
        // ~1.8s + 点开界面 ~0.2s, 界面刚开 containerSubTask 就判"无事可做"
        // (产物没烧好+料够) → 立刻派守卫, 守卫首 tick closeScreen() → 界面总
        // 显示 <0.3s 就被关, 下轮再开, 反复 11 轮 (tasks.log 里
        // "STOP <Doing something to closest block...> by <Guarding furnace> took=2.0s")。
        // latch 让界面至少显示 MIN_OPEN_SECONDS 再交给守卫关屏; 期间若烧出
        // 产物/缺料, 前方 Receiving Output / Filling fuel 分支照常先命中, 不误事。
        private static final double MIN_OPEN_SECONDS = 3.0;
        private final TimerGame _openHoldTimer = new TimerGame(MIN_OPEN_SECONDS);
        private boolean _wasContainerOpen = false;

        /**
         * (2026-09-08 诊断) 缓存快照: 材料槽/输出槽/燃料槽/烧制进度。
         */
        public String describeFurnaceCache() {
            return "[mat=" + _furnaceCache.materialSlot.getItem() + " x" + _furnaceCache.materialSlot.getCount()
                    + "][out=" + _furnaceCache.outputSlot.getItem() + " x" + _furnaceCache.outputSlot.getCount()
                    + "][fuel=" + _furnaceCache.fuelSlot.getItem() + " x" + _furnaceCache.fuelSlot.getCount()
                    + "][burn=" + String.format("%.2f", _furnaceCache.burnPercentage) + "]";
        }

        @Override
        protected boolean isSubTaskEqual(DoStuffInContainerTask other) {
            if (other instanceof DoSmeltInFurnaceTask task) {
                return task._target.equals(_target) && task._ignoreMaterials == _ignoreMaterials;
            }
            return false;
        }

        @Override
        protected boolean isContainerOpen(AltoClef mod) {
            return (mod.getPlayer().currentScreenHandler instanceof FurnaceScreenHandler);
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            super.onStop(mod, interruptTask);
            // (2026-09-07 熔炉守卫) 被外部打断 (gear 顶替/用户取消) 时清守卫状态:
            // 任务重跑从头走 walk&open, 防止旧守卫实例把重启后的任务又拉回守卫分支。
            _guardActive = false;
            _guard = null;
        }

        @Override
        protected Task onTick(AltoClef mod) {
            // (2026-09-12 界面闪烁修复) 界面一旦关闭就复位开屏 latch 标记, 下一次
            // 打开重新计时。放在守卫闸门之前 —— 守卫关屏后本方法提前 return,
            // 不经过这里就会漏掉复位。
            if (!isContainerOpen(mod)) {
                _wasContainerOpen = false;
            }
            // (2026-09-08 记得管理) 掉落捡回保活: 记忆中的炉子被毁时, 材料以掉落物
            // 形式散在原地 (5 分钟 despawn), 先捡回再继续。缓存实例保活 (勿一次性
            // new 后靠标志防重入)。
            if (_recoverTask != null) {
                if (_recoverTask.isFinished(mod)) {
                    _recoverTask = null;
                } else {
                    setDebugState("Recovering drops from destroyed furnace");
                    return _recoverTask;
                }
            }
            // (2026-09-07 熔炉守卫接管分支) 守卫激活且未驻留期满时, 直接返回
            // 守卫, 不走下方原有流程 —— 否则父类 DoStuffInContainerTask.onTick
            // 每 tick 重判 isContainerOpen=false 会立刻切回 walk&open, 守卫活
            // 不过 1 tick。驻留期满 → 恢复原流程 → walk&reopen 重开熔炉 →
            // containerSubTask 收输出/补燃料 → 再次 Waiting 再次守卫, 循环直至
            // isFurnaceDrained。
            if (_guardActive) {
                if (_guard == null || _guard.isFinished(mod)) {
                    _guardActive = false;
                    _guard = null;
                } else {
                    return _guard;
                }
            }
            tryUpdateOpenFurnace(mod);
            checkFurnaceStillThere(mod);
            // Include both regular + optional items
            ItemTarget materialTarget = _allMaterials;
            ItemTarget outputTarget = _target.getItem();
            // Materials needed = (mat_target (- 0*mat_in_inventory) - out_in_inventory - mat_in_furnace - out_in_furnace)
            // ^ 0 * mat_in_inventory because we always care aobut the TARGET materials, not how many LEFT there are.
            int materialsNeeded = materialTarget.getTargetCount()
                    /*- mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches())*/ // See comment above
                    - StorageHelper.countItemsInInventoryReal(mod, outputTarget.getMatches())
                    - (materialTarget.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0)
                    - (outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0);
            double totalFuelInFurnace = ItemHelper.getFuelAmount(_furnaceCache.fuelSlot) + _furnaceCache.burningFuelCount + _furnaceCache.burnPercentage;
            // Fuel needed = (mat_target - out_in_inventory - out_in_furnace - totalFuelInFurnace)
            double fuelNeeded = _ignoreMaterials
                        ? Math.min(materialTarget.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0, materialTarget.getTargetCount())
                        : materialTarget.getTargetCount()
                    /* - mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches()) */
                    - StorageHelper.countItemsInInventoryReal(mod, outputTarget.getMatches())
                    - (outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0)
                    - totalFuelInFurnace;
            // 一次烧满：炉里已塞入的材料要全部烧完——燃料需求至少覆盖炉内
            // 材料量，否则燃料烧尽后剩余材料会滞留在炉中。
            if (materialTarget.matches(_furnaceCache.materialSlot.getItem())) {
                fuelNeeded = Math.max(fuelNeeded, _furnaceCache.materialSlot.getCount() - totalFuelInFurnace);
            }

            // We don't have enough materials...
            // (2026-08-24 真实背包兜底) 材料判定改用真实背包计数: tracker 缓存
            // 可能陈旧 (用户实测背包有 22 生金矿却判缺材料 → 挖 raw_gold → 回
            // 主世界造门), 必须以真实背包为准。
            // (2026-09-08 记得管理) 孤儿炉债优先于猎材料: 任务转投新炉后, 旧炉里
            // 还躺着我们的肉 —— 先取回旧料, 别跑去猎牛/收集新料把它晾在原地
            // (17:13 实测: 牛肉x8 滞留旧炉, 任务转头猎牛, 牛肉被遗忘)。
            if (_recoverTask == null && !ORPHAN_FURNACES.isEmpty()) {
                OrphanFurnace orphan = ORPHAN_FURNACES.remove(0);
                Debug.logToFile("altoclef_diag.log", "[SmeltOrphan]\tclaimed " + orphan.pos
                        + " raw=" + orphan.raw + " x" + orphan.count);
                _recoverTask = new RecoverFurnaceTask(orphan.pos, orphan.raw, orphan.cooked);
            }
            if (StorageHelper.countItemsInInventoryReal(mod, materialTarget.getMatches()) < materialsNeeded) {
                setDebugState("Getting Materials");
                return getMaterialTask(_target.getMaterial());
            }

            // We don't have enough fuel...
            boolean fuelShort = StorageHelper.calculateInventoryFuelCount(mod) < fuelNeeded;
            if (fuelShort || (_fuelCommitted && !_fuelCommitTimer.elapsed())) {
                // 提交期: 进入 Get Fuel 后持续返回 CollectFuelTask。燃料计数
                // 可能因合成格/光标里的木板瞬时虚高, 但木板未落袋不能算够,
                // 期间容器分支 (合成/放置熔炉) 不得抢断。
                if (!_fuelCommitted) {
                    _fuelCommitted = true;
                    _fuelCommitTimer.reset();
                }
                CollectFuelTask fuelTask = new CollectFuelTask(fuelNeeded);
                if (!fuelTask.isFinished(mod)) {
                    setDebugState("Getting Fuel");
                    return fuelTask;
                }
                // 燃料真够 (木板已落袋) → 解除提交, 走容器分支
                _fuelCommitted = false;
            }
            if (_fuelCommitTimer.elapsed()) {
                _fuelCommitted = false;
            }

            // Make sure our materials are accessible in our inventory
            if (StorageHelper.isItemInaccessibleToContainer(mod, _allMaterials)) {
                return new MoveInaccessibleItemToInventoryTask(_allMaterials);
            }

           // Make sure we have room for the output in our inventory
            EnsureFreeInventorySlotTask _freeInventoryTask = new EnsureFreeInventorySlotTask();
            if (_freeInventoryTask.isActive() && !_freeInventoryTask.isFinished(mod) && !mod.getItemStorage().hasEmptyInventorySlot()) {
                setDebugState("Freeing inventory.");
                return _freeInventoryTask;
            }
            // We have fuel and materials and there is a free space in the inventory. Get to our container and smelt!
            return super.onTick(mod);
        }

        // Override this if our materials must be acquired in a special way.
        // virtual
        protected Task getMaterialTask(ItemTarget target) {
            return TaskCatalogue.getItemTask(target);
        }

        @Override
        protected Task containerSubTask(AltoClef mod) {
            // (2026-09-12 界面闪烁修复) 开屏 latch 起点: 本方法只在界面打开时被
            // 调用, 首次进来即"界面刚打开" → 重置最短显示计时 (之后每 tick 进来
            // 不再重置, 计时连续累积, 直到 onTick 看到界面关闭才复位标记)。
            if (!_wasContainerOpen) {
                _wasContainerOpen = true;
                _openHoldTimer.reset();
            }

            // We have appropriate materials/fuel.
            /*
             * - If output slot has something, receive it.
             * - Calculate needed material input. If we don't have, put it in.
             * - Calculate needed fuel input. If we don't have, put it in.
             * - Wait lol
             */
            ItemStack output   = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
            ItemStack material = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
            ItemStack fuel     = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);

            // (2026-08-22 收铁卡死修复) 收走熔炉输出后产物在光标上（PICKUP
            // 只进光标），下一 tick output 已空会跳过下面 Receiving Output
            // 分支 → 铁锭滞留光标；叠加 isFinished 的 NoCursor 判定永不达标
            // → 卡死在熔炉界面（用户实测 3 分 44 秒无日志）。这里兜底：光标
            // 非空立即放回背包；放不下则关屏让 MC 自动把光标物品弹回物品栏
            // /掉落，总比光标占位阻塞后续一切容器/合成操作强。
            ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
            // (2026-08-24 拿起放下反复修复) 光标是"正在搬往炉子"的材料/燃料时
            // 不 Dumping。containerSubTask 每 tick 执行, 旧逻辑只要光标非空
            // (MoveItemToSlotFromInventoryTask PICKUP 后) 就 Dumping 放回背包
            // → 拿起又放下反复 (用户实测 4-5 次)。只有光标是其他杂物才放回
            // (保留原收铁卡死修复语义)。
            boolean movingTarget = _allMaterials.matches(cursor.getItem())
                    || ItemHelper.isFuel(cursor.getItem());
            if (!cursor.isEmpty() && !movingTarget) {
                Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(cursor, false);
                if (toFit.isPresent()) {
                    setDebugState("Dumping cursor stack");
                    return new ClickSlotTask(toFit.get());
                }
                setDebugState("Closing screen to free cursor stack");
                StorageHelper.closeScreen();
                return null;
            }

            // Receive from output if present
            if (!output.isEmpty()) {
                setDebugState("Receiving Output");
                // Ensure our cursor is empty/can receive our item
                ItemStack outCursor = StorageHelper.getItemStackInCursorSlot();
                if (!ItemHelper.canStackTogether(output, outCursor)) {
                    // (2026-09-01 同 LootContainerTask 根因) 删 `.or(getGarbageSlot)`
                    // 回退 — 背包满时点垃圾槽只交换不扔, 光标垃圾永远腾不掉, 收输出
                    // 与点垃圾槽无限互换。无空位时直接扔光标(非材料/燃料的杂物), 腾出
                    // 光标收输出。
                    Optional<Slot> toFit = mod.getItemStorage().getSlotThatCanFitInPlayerInventory(outCursor, false);
                    if (toFit.isPresent()) {
                        return new ClickSlotTask(toFit.get());
                    } else {
                        // Eh screw it — 背包满放不下 → 扔光标杂物腾光标
                        return new ThrowCursorTask();
                    }
                }
                // (2026-09-05 收产物改 QUICK_MOVE, 用户"金子拿起又放下4次") 原来用
                // PICKUP 把产物拿到光标 → 下一 tick 本任务的 Dumping 光标守卫
                // (movingTarget 只豁免材料/燃料, 产物不在豁免列) 立刻把它放回背包
                // → 每块金锭烧好都"拿起→放下"一轮 (18:14:22 / 18:14:32 日志各一轮,
                // 槽位点击 8(输出槽拿起) ↔ 11(背包放下))。改 QUICK_MOVE(shift-click)
                // 直接入包, 光标全程不经手产物, 一滴	clicks 都省; 光标腾位守卫保留
                // (QUICK_MOVE 不碰光标, 但光标有杂物时仍先清走更稳)。
                return new ClickSlotTask(FurnaceSlot.OUTPUT_SLOT, SlotActionType.QUICK_MOVE);
            }

            // Fill in input if needed
            // (2026-08-27 改为"按需喂料"见下) 旧注释的"材料槽塞满"是 20 生铁残留被弃
            // 的根因, 已放弃。
            ItemTarget materialTarget = _allMaterials;

            // (2026-09-08 换肉 swap 修复) 输入槽被"非本任务材料"占据时, 直接把新料
            // 点上去会发生槽位 SWAP (羊肉进、牛肉 11 个弹到光标, 再被光标守卫倒回
            // 背包) —— 用户实测"牛肉放进去还没烧就被羊肉换上去"的物理过程。
            // 先把异料取出到光标 (下一 tick 光标守卫倒回背包), 输入槽空了再喂新料。
            if (!material.isEmpty() && !_allMaterials.matches(material.getItem())) {
                setDebugState("Taking out foreign material: " + material.getItem());
                return new ClickSlotTask(FurnaceSlot.INPUT_SLOT_MATERIALS);
            }

            int materialsAlreadyIn = (materialTarget.matches(material.getItem()) ? material.getCount() : 0);
            // (2026-08-27 不再过量喂料, 用户方案) 只喂到目标输出所需的原料量, 别把整堆
            // 原料都塞进炉子 — 否则 target 到量就结束, 炉里剩下的生铁随熔炉一起被弃
            // (永久丢, 用户实测炉里留 20+ 生铁, "带走残留"方案又被 gear 抢断不可靠)。
            // 多余原料留在背包, 下一批需要时再喂, 炉里永远不会残留可被遗弃的原料。
            int neededMaterialsInSlot = materialTarget.getTargetCount()
                    - mod.getItemStorage().getItemCountInventoryOnly(_target.getItem().getMatches())
                    - (_target.getItem().matches(output.getItem()) ? output.getCount() : 0);
            // 至少保持槽里已有, 且不超 64
            neededMaterialsInSlot = Math.min(64, Math.max(neededMaterialsInSlot, materialsAlreadyIn));
            // We don't have the right material or we need more
            // (2026-09-10 x0 派发守卫) 需要补入量 <= 0 (目标已由背包/炉内满足)
            // 时不再派发 MoveItemToSlotFromInventoryTask — 旧逻辑材料槽为空
            // 就无条件进分支, 算出 x0 任务: 子任务没料可拿 → logError + return
            // null 死等 (isFinished 又要求目标槽出现材料, 永不成立)。
            if ((!_allMaterials.matches(material.getItem()) || neededMaterialsInSlot > material.getCount())
                    && neededMaterialsInSlot - materialsAlreadyIn > 0) {
                setDebugState("Moving Materials");
                return new MoveItemToSlotFromInventoryTask(new ItemTarget(materialTarget, neededMaterialsInSlot - materialsAlreadyIn), FurnaceSlot.INPUT_SLOT_MATERIALS);
            }

            /*
            double currentFuel = _ignoreMaterials
                    ? (Math.min(materialTarget.matches(_furnaceCache.materialSlot.getItem()) ? _furnaceCache.materialSlot.getCount() : 0, materialTarget.getTargetCount())
                    : materialTarget.getTargetCount()
                    - mod.getItemStorage().getItemCountInventoryOnly(materialTarget.getMatches())
                    - mod.getItemStorage().getItemCountInventoryOnly(outputTarget.getMatches())
                    - (outputTarget.matches(_furnaceCache.outputSlot.getItem()) ? _furnaceCache.outputSlot.getCount() : 0)
                    - totalFuelInFurnace;
             */
            // Fill in fuel if needed
            if (fuel.isEmpty() || !ItemHelper.isFuel(fuel.getItem())) {
                double currentlyCached = StorageHelper.getFurnaceFuel() + StorageHelper.getFurnaceCookPercent();
                double needs = material.getCount() - currentlyCached;
                if (needs > 0) {
                    // Get best fuel to fill
                    double closestDelta = Double.NEGATIVE_INFINITY;
                    ItemStack bestStack = null;
                    for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
                        if (mod.getModSettings().isSupportedFuel(stack.getItem())) {
                            double fuelAmount = ItemHelper.getFuelAmount(stack.getItem()) * stack.getCount();
                            double delta = needs - fuelAmount;
                            if (
                                    (bestStack == null) ||
                                    // If our best is above, prioritize lower values
                                    (closestDelta > 0 && delta < closestDelta) ||
                                    // If our best is below, prioritize higher below values
                                    (closestDelta < 0 && delta < 0 && delta > closestDelta)
                            ) {
                                bestStack = stack;
                                closestDelta = delta;
                            }
                        }
                    }
                    if (bestStack != null) {
                        // (2026-09-01 用户"多余燃料留在炉里被弃") 旧逻辑把 bestStack
                        // 整组 (如整叠煤=8×N 次烧炼) 全搬进燃料槽, 烧完目标还剩一堆
                        // 燃料残留在炉里, 收炉时被遗弃/丢失。改为只搬"满足当前 needs
                        // 所需的最少个数" — needs 单位是烧炼次数, perItem 是单件能烧
                        // 几次。ceil 取整(不足 1 件按 1 件), 上限不超背包实际持有。
                        double perItem = ItemHelper.getFuelAmount(bestStack.getItem());
                        int countToMove = perItem > 0
                                ? Math.min(bestStack.getCount(), Math.max(1, (int) Math.ceil(needs / perItem)))
                                : bestStack.getCount();
                        setDebugState("Filling fuel");
                        return new MoveItemToSlotFromInventoryTask(new ItemTarget(bestStack.getItem(), countToMove), FurnaceSlot.INPUT_SLOT_FUEL);
                    }
                }
            }

            // (2026-09-12 界面闪烁修复) 开屏 latch 闸门: 界面显示时长不足
            // MIN_OPEN_SECONDS 时先不交给守卫 —— 守卫首 tick 就 closeScreen(),
            // 界面开了 <0.3s 即被关掉是本轮"一闪一闪"的直接成因。这里 return
            // null 保持界面开着, 不派子任务; 计时到点才落到下方守卫分支关屏。
            // 前方 Receiving Output / Filling fuel 分支已先执行, 故 latch 期间
            // 真有产物或缺料时不受影响。
            if (!_openHoldTimer.elapsed()) {
                setDebugState("Holding furnace UI open (anti-flicker latch)");
                return null;
            }

            // (2026-09-07 熔炉守卫, 用户"烧东西时不能一直在烧制界面") 等待期
            // 不再 return null 抱着界面: 激活守卫子任务 (关屏、站炉边扫视、
            // 16 格内敌对生物派杀), 驻留期满由本类 onTick 恢复 super.onTick
            // 重开熔炉收货/补料。炉位取 override (近场真实熔炉扫描) 优先,
            // 退回缓存位置; 两者都空则退化为旧"抱界面等待"。
            BlockPos furnacePos = overrideContainerPosition(mod);
            if (furnacePos == null) furnacePos = getTargetContainerPosition();
            if (furnacePos != null) {
                _guard = new FurnaceSmeltGuardTask(furnacePos);
                _guardActive = true;
                setDebugState("Handing over to furnace guard");
                return _guard;
            }
            setDebugState("Waiting...");
            return null;
        }

        @Override
        protected double getCostToMakeNew(AltoClef mod) {
            // NOTE: _furnaceCache is NEVER null (initialized in the constructor),
            // so the old `if (_furnaceCache != null) return 9999999;` made this
            // method always return 9999999 — the "walk to existing furnace vs
            // craft a new one" cost comparison in DoStuffInContainerTask was
            // dead code. Only check it when the furnace is ACTIVELY smelting
            // (has materials/fuel/burn progress): then never make a new one.
            boolean furnaceBusy = !_furnaceCache.materialSlot.isEmpty()
                    || !_furnaceCache.fuelSlot.isEmpty()
                    || _furnaceCache.burnPercentage > 0
                    || _furnaceCache.burningFuelCount > 0;
            if (furnaceBusy) {
                // TODO: If we're already smelting, get cost for materials (for now just set to really high number or something)
                return 9999999;
            }
            // We got stone
            if (mod.getItemStorage().getItemCount(Items.COBBLESTONE) > 8) {
                double cost = 100 - (90 * (double) mod.getItemStorage().getItemCount(Items.COBBLESTONE) / 8);
                return Math.max(cost, 10);
            }
            // We got pick
            if (StorageHelper.miningRequirementMetInventory(mod, MiningRequirement.WOOD)) {
                return 50;
            }
            // We gotta make pick and mine stone
            return 100;
        }

        @Override
        protected double getMaxWalkDistance(AltoClef mod) {
            // (2026-09-03 远熔炉该现做而非远走, 用户"定位特别远的熔炉不如现做一个")
            // 已有熔炉超过 40 格就不去, 走现做(先挖/找圆石做熔炉)就近烧, 不上百格远赴。
            return 40;
        }

        @Override
        protected BlockPos overrideContainerPosition(AltoClef mod) {
            // (2026-08-24 震荡修复) 优先返回玩家附近【真实存在】的熔炉 (直接扫
            // 世界方块, 不依赖 BlockTracker)。原因: 围蔽墙里新放置的熔炉可能
            // 未被 chunk 扫描及时登记 → DoStuffInContainerTask 的 nearest 为空
            // → 走"获取容器物品"分支 → 背包已无熔炉物品 (放置成方块了) → 去
            // 合成熔炉 (下界无圆石) → 卡死 (用户实测 11:45 Getting container
            // item ↔ Crafting planks ↔ Breaking the crafting grid 反复横跳)。
            // 紧邻真实熔炉存在时直接返回它, 让任务走过去打开, 不再触发获取。
            BlockPos player = mod.getPlayer().getBlockPos();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos pos = player.add(dx, dy, dz);
                        if (mod.getWorld().getBlockState(pos).getBlock() == Blocks.FURNACE
                                && pos.isWithinDistance(player, 2.5)) {
                            return pos;
                        }
                    }
                }
            }
            // If we have a valid container position, KEEP it.
            return getTargetContainerPosition();
        }

        private void tryUpdateOpenFurnace(AltoClef mod) {
            if (isContainerOpen(mod)) {
                // (2026-09-08 记得管理) 熔炉身份校验: 缓存必须绑定"具体哪一座炉"。
                // 若当前目标炉坐标变了 (旧炉被毁后转投新炉/另寻炉), 旧炉未排空的
                // 内容绝不能被新炉的空槽覆盖成"已排空" (17:13 实测牛肉x8 被空炉
                // 数据清零 → 上层误弃缓存)。换炉 → 缓存置 UNKNOWN, 保守等下一次
                // 实际观察。
                BlockPos openPos = getTargetContainerPosition();
                if (openPos != null) {
                    if (_cacheKnown && _cachePos != null && !openPos.equals(_cachePos)) {
                        // (2026-09-08 记得管理) 旧炉里还有我们的东西 → 记孤儿账,
                        // 由本任务 onTick 排队取回; 没有残留才直接作废。
                        if (!_furnaceCache.materialSlot.isEmpty() || !_furnaceCache.outputSlot.isEmpty()) {
                            ORPHAN_FURNACES.add(new OrphanFurnace(_cachePos,
                                    _target.getMaterial().getMatches()[0],
                                    _target.getItem().getMatches()[0],
                                    _furnaceCache.materialSlot.getCount()));
                            Debug.logToFile("altoclef_diag.log", "[SmeltOrphan]\trecorded " + _cachePos
                                    + " mat=" + _furnaceCache.materialSlot.getItem() + " x"
                                    + _furnaceCache.materialSlot.getCount() + " (switched to " + openPos + ")");
                        }
                        Debug.logToFile("altoclef_diag.log", "[SmeltCache]\tFURNACE SWITCHED "
                                + _cachePos + " -> " + openPos + " (old cache=" + describeFurnaceCache()
                                + ") -> cache marked UNKNOWN");
                        _cacheKnown = false;
                    }
                    _cachePos = openPos;
                }
                // Update current furnace cache
                _furnaceCache.burnPercentage = StorageHelper.getFurnaceCookPercent();
                _furnaceCache.burningFuelCount = StorageHelper.getFurnaceFuel();
                _furnaceCache.fuelSlot = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_FUEL);
                ItemStack newMaterial = StorageHelper.getItemStackInSlot(FurnaceSlot.INPUT_SLOT_MATERIALS);
                ItemStack newOutput = StorageHelper.getItemStackInSlot(FurnaceSlot.OUTPUT_SLOT);
                // (2026-09-08 诊断) 材料槽/输出槽变化才记, 抓"肉进炉但缓存没跟上"的时序
                if (newMaterial.getItem() != _furnaceCache.materialSlot.getItem()
                        || newMaterial.getCount() != _furnaceCache.materialSlot.getCount()
                        || newOutput.getItem() != _furnaceCache.outputSlot.getItem()
                        || newOutput.getCount() != _furnaceCache.outputSlot.getCount()) {
                    Debug.logToFile("altoclef_diag.log", "[SmeltCache]\tmat=" + newMaterial.getItem() + " x"
                            + newMaterial.getCount() + " out=" + newOutput.getItem() + " x" + newOutput.getCount()
                            + " (was mat=" + _furnaceCache.materialSlot.getItem() + " x"
                            + _furnaceCache.materialSlot.getCount() + " out=" + _furnaceCache.outputSlot.getItem()
                            + " x" + _furnaceCache.outputSlot.getCount() + ")");
                }
                _furnaceCache.materialSlot = newMaterial;
                _furnaceCache.outputSlot = newOutput;
                _cacheKnown = true;
            }
        }

        /**
         * (2026-09-08 记得管理) 记忆中的炉子还在不在: 缓存显示炉里还有我们的
         * 材料、bot 就在炉边、但方块已不是熔炉 → 炉子被毁 (苦力怕/意外), 内容
         * 已变掉落物散在原地。立即捡回掉落并作废缓存 (unknown), 保活不弃任务。
         * 1 秒节流; 界面开着时方块必在, 跳过。
         */
        private void checkFurnaceStillThere(AltoClef mod) {
            if (!_cacheKnown || _cachePos == null || _furnaceCache.materialSlot.isEmpty()) return;
            if (!_furnaceGoneCheckTimer.elapsed()) return;
            _furnaceGoneCheckTimer.reset();
            if (isContainerOpen(mod)) return;
            if (mod.getPlayer().getBlockPos().isWithinDistance(_cachePos, 6)
                    && mod.getWorld().getBlockState(_cachePos).getBlock() != Blocks.FURNACE) {
                Item raw = _target.getMaterial().getMatches()[0];
                int count = _furnaceCache.materialSlot.getCount();
                Debug.logToFile("altoclef_diag.log", "[SmeltFurnaceGone]\t" + _cachePos + " vanished with mat="
                        + _furnaceCache.materialSlot.getItem() + " x" + count + " -> recovering drops");
                // 缓存作废: 炉子没了, 内容已掉落。置 unknown 防"伪排空"弃缓存。
                _cacheKnown = false;
                _cachePos = null;
                _furnaceCache.materialSlot = ItemStack.EMPTY;
                _furnaceCache.fuelSlot = ItemStack.EMPTY;
                _furnaceCache.outputSlot = ItemStack.EMPTY;
                _furnaceCache.burnPercentage = 0;
                _furnaceCache.burningFuelCount = 0;
                // 多材料场景兜底: 匹配数组里第一个即生肉 (烧肉只有单一生料)
                _recoverTask = new PickupDroppedItemTask(new ItemTarget(raw, count), true);
            }
        }

        private final TimerGame _furnaceGoneCheckTimer = new TimerGame(1);
        private Task _recoverTask = null;
    }

    static class FurnaceCache {
        public ItemStack materialSlot = ItemStack.EMPTY;
        public ItemStack fuelSlot = ItemStack.EMPTY;
        public ItemStack outputSlot = ItemStack.EMPTY;
        public double burningFuelCount;
        public double burnPercentage;
    }

    // ==================== (2026-09-08 记得管理) 孤儿炉账本 ====================

    /** 转投他炉时旧炉残留的一条账 (静态跨任务实例, 会话级)。 */
    private static class OrphanFurnace {
        final BlockPos pos;
        final Item raw;
        final Item cooked;
        final int count;
        OrphanFurnace(BlockPos pos, Item raw, Item cooked, int count) {
            this.pos = pos; this.raw = raw; this.cooked = cooked; this.count = count;
        }
    }

    private static final List<OrphanFurnace> ORPHAN_FURNACES = new ArrayList<>();

    /**
     * 孤儿炉回收任务: 走回旧炉开门, 把材料槽/输出槽里属于我们的东西
     * QUICK_MOVE 回背包 (燃料留下复用, 别的任务放的料不动)。炉子没了 →
     * 就地捡掉落 (只试一轮, 防无掉落时无限循环)。
     */
    public static class RecoverFurnaceTask extends Task {
        private final BlockPos _pos;
        private final Item[] _targets;
        private boolean _pickupTried = false;
        private boolean _done = false;

        RecoverFurnaceTask(BlockPos pos, Item raw, Item cooked) {
            _pos = pos;
            _targets = new Item[]{raw, cooked};
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (_done) return null;
            boolean furnaceThere = mod.getWorld().getBlockState(_pos).getBlock() == Blocks.FURNACE;
            if (!furnaceThere) {
                if (_pickupTried) {
                    _done = true;
                    return null;
                }
                _pickupTried = true;
                Debug.logToFile("altoclef_diag.log", "[SmeltOrphan]\t" + _pos + " block gone, picking up drops");
                return new PickupDroppedItemTask(new ItemTarget(_targets), true);
            }
            if (!(mod.getPlayer().currentScreenHandler instanceof FurnaceScreenHandler)) {
                setDebugState("Walking back to abandoned furnace " + _pos.toShortString());
                return new InteractWithBlockTask(_pos);
            }
            for (FurnaceSlot slot : new FurnaceSlot[]{FurnaceSlot.INPUT_SLOT_MATERIALS, FurnaceSlot.OUTPUT_SLOT}) {
                ItemStack stack = StorageHelper.getItemStackInSlot(slot);
                if (stack.isEmpty()) continue;
                boolean ours = false;
                for (Item t : _targets) {
                    if (stack.getItem() == t) { ours = true; break; }
                }
                if (!ours) continue; // 别的任务放的料 (如羊肉) 不动
                if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(stack, true).isEmpty()) {
                    setDebugState("Making room before recovery");
                    return new EnsureFreeInventorySlotTask();
                }
                setDebugState("Recovering " + stack.getItem() + " x" + stack.getCount() + " from orphan furnace");
                return new ClickSlotTask(slot, SlotActionType.QUICK_MOVE);
            }
            _done = true;
            return null;
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return _done;
        }

        @Override
        protected void onStart(AltoClef mod) {
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            // 被打断时别把熔炉界面留在屏幕上
            StorageHelper.closeScreen();
        }

        @Override
        public String toDebugString() {
            return "Recover furnace contents @" + _pos.toShortString();
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof RecoverFurnaceTask task && task._pos.equals(_pos);
        }
    }
}
