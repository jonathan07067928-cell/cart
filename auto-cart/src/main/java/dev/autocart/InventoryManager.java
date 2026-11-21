package dev.autocart;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Moves required items into the hotbar using normal inventory SWAP clicks (the same packets
 * as pressing a number key over a slot) and undoes those swaps afterwards.
 */
final class InventoryManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCart");

    /** srcMenuSlot = container slot (9..35) the item came from; item = what was swapped into the hotbar. */
    private record Swap(int srcMenuSlot, int hotbarSlot, Item item) {}

    private final Deque<Swap> swaps = new ArrayDeque<>();
    private int originalSelected = -1;

    void begin(LocalPlayer p) {
        swaps.clear();
        originalSelected = McCompat.selectedSlot(p);
    }

    static int findInHotbar(LocalPlayer p, Predicate<ItemStack> match) {
        for (int i = 0; i < 9; i++) if (match.test(p.getInventory().getItem(i))) return i;
        return -1;
    }

    private static int findInMain(LocalPlayer p, Predicate<ItemStack> match) {
        for (int i = 9; i < 36; i++) if (match.test(p.getInventory().getItem(i))) return i;
        return -1;
    }

    /** @return hotbar slot now holding a matching stack, or -1 if the item is not in the inventory. */
    int ensureInHotbar(Minecraft mc, Predicate<ItemStack> match, Set<Integer> reserved) {
        LocalPlayer p = mc.player;
        int h = findInHotbar(p, match);
        if (h >= 0) return h;

        int src = findInMain(p, match);
        if (src < 0) return -1;

        int dst = pickTarget(p, reserved);
        if (dst < 0) return -1;

        Item item = p.getInventory().getItem(src).getItem();
        mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, src, dst, ClickType.SWAP, p);
        swaps.push(new Swap(src, dst, item));
        return dst;
    }

    private static int pickTarget(LocalPlayer p, Set<Integer> reserved) {
        for (int i = 0; i < 9; i++) {
            if (!reserved.contains(i) && p.getInventory().getItem(i).isEmpty()) return i;
        }
        int sel = McCompat.selectedSlot(p);
        if (!reserved.contains(sel)) return sel;
        for (int i = 0; i < 9; i++) if (!reserved.contains(i)) return i;
        return -1;
    }

    /** Undo all swaps (newest first) and restore the originally selected hotbar slot. */
    void restore(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) {
            swaps.clear();
            return;
        }
        if (p.containerMenu != p.inventoryMenu) {
            if (!swaps.isEmpty()) LOGGER.warn("A container is open; cannot restore inventory arrangement safely.");
            swaps.clear();
            return;
        }
        while (!swaps.isEmpty()) {
            Swap s = swaps.pop();
            ItemStack inSlot = p.getInventory().getItem(s.hotbarSlot());
            // Only undo if the swap really happened (item is still there, or was fully consumed).
            if (inSlot.isEmpty() || inSlot.is(s.item())) {
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, s.srcMenuSlot(), s.hotbarSlot(), ClickType.SWAP, p);
            }
        }
        if (originalSelected >= 0) McCompat.selectSlot(p, originalSelected);
        originalSelected = -1;
    }
}
