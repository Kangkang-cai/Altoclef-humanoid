package adris.altoclef.util.slots;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.*;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.Pair;
import org.apache.commons.lang3.NotImplementedException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

@SuppressWarnings("rawtypes")
public class SlotScreenMapping {

    // Order here matters as whoever returns "true" in the predicate first is picked.
    private static final List<SlotScreenMappingEntry> _classList = List.of(
            e(CraftingTableSlot.class, screen -> screen instanceof CraftingScreen, CraftingTableSlot::new),
            e(FurnaceSlot.class, screen -> screen instanceof AbstractFurnaceScreen, FurnaceSlot::new),
            e(SmithingTableSlot.class, screen -> screen instanceof SmithingScreen, SmithingTableSlot::new),
            e(BrewingStandSlot.class, screen -> screen instanceof BrewingStandScreen, BrewingStandSlot::new),
            e(ChestSlot.class, screen -> screen instanceof GenericContainerScreen, ChestSlot::new),
            // 修复 (ping-pong 2026-08-21 12:36 "卡在熔炉界面"): 原谓词是
            // screen -> true, 导致熔炉/箱子等界面打开时 PlayerSlot 合成格
            // (window slot 1-4) 的 isScreenOpen() 仍为 true, conversion slot
            // 检查会误读当前容器的同名槽位 (熔炉 slot 1 = 燃料槽!), 燃料槽
            // 里的木板被计入玩家木板数 → "材料够/不够" 随界面开关横跳。
            // 2x2 合成格只在玩家背包界面 (InventoryScreen) 可见; 无界面时
            // 保留 true 以兼容 getFromScreen 的槽位映射 (PlayerScreenHandler)。
            e(PlayerSlot.class, screen -> screen == null || screen instanceof InventoryScreen, PlayerSlot::new), // Order matters, leave this BEFORE the BACK!
            e(CursorSlot.class, screen -> true, (slot, inv) -> CursorSlot.SLOT) // Order matters, leave this in the BACK!
    );

    @SuppressWarnings("unchecked")
    public static boolean isScreenOpen(Class slotType) {
        Screen screen = MinecraftClient.getInstance().currentScreen;
        for (SlotScreenMappingEntry entry : _classList) {
            if (slotType == entry.type || slotType.isAssignableFrom(entry.type)) {
                return entry.inScreen.test(screen);
            }
        }
        throw new NotImplementedException("Slot type class not registered in SlotScreenMapping: " + slotType + ". Please register! (current screen = " + screen + ")");
    }

    public static Slot getFromScreen(int slot, boolean inventory) {
        Screen screen = MinecraftClient.getInstance().currentScreen;
        for (SlotScreenMappingEntry entry : _classList) {
            if (entry.inScreen.test(screen)) {
                return entry.getSlot.apply(slot, inventory);
            }
        }
        throw new NotImplementedException("We should never get here, _classList should be filled with a predicate that always returns true at the bottom (for PlayerSlot & CursorSlot)");
    }


    private static SlotScreenMappingEntry e(Class type, Predicate<Screen> inScreen, BiFunction<Integer, Boolean, Slot> getSlot) {
        return new SlotScreenMappingEntry(type, inScreen, getSlot);
    }

    static class SlotScreenMappingEntry {
        public Class type;
        public Predicate<Screen> inScreen;
        public BiFunction<Integer, Boolean, Slot> getSlot;
        public SlotScreenMappingEntry(Class type, Predicate<Screen> inScreen, BiFunction<Integer, Boolean, Slot> getSlot) {
            this.type = type;
            this.inScreen = inScreen;
            this.getSlot = getSlot;
        }
    }
}
