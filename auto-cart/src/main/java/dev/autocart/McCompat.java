package dev.autocart;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every Minecraft call whose name/signature is likely to change between versions lives here.
 * If a Minecraft update breaks the build, the fix should almost always be in this file only.
 */
final class McCompat {
    private McCompat() {}

    static double blockRange(LocalPlayer p) { return p.blockInteractionRange(); }

    static double entityRange(LocalPlayer p) { return p.entityInteractionRange(); }

    static boolean screenOpen(Minecraft mc) { return mc.screen != null; }

    static int selectedSlot(LocalPlayer p) { return p.getInventory().getSelectedSlot(); }

    static void selectSlot(LocalPlayer p, int slot) { p.getInventory().setSelectedSlot(slot); }

    static boolean isCharged(ItemStack s) { return !s.isEmpty() && CrossbowItem.isCharged(s); }

    static void overlay(Minecraft mc, Component text) { mc.gui.setOverlayMessage(text, false); }

    /** Ping in ticks, from the tab-list latency (0 in singleplayer). */
    static int pingTicks(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return 0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : (int) Math.ceil(info.getLatency() / 50.0);
    }

    /** Right-click a block with the main hand (sends the normal use-item-on packet with a sequence id). */
    static boolean useItemOn(Minecraft mc, BlockHitResult hit) {
        InteractionResult r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        return r.consumesAction();
    }

    /** Right-click an entity the same way vanilla does: interactAt first, then interact if not consumed. */
    static boolean interactEntity(Minecraft mc, Entity target, Vec3 hitPoint) {
        MultiPlayerGameMode gm = mc.gameMode;
        InteractionResult r = gm.interactAt(mc.player, target, new EntityHitResult(target, hitPoint), InteractionHand.MAIN_HAND);
        if (!r.consumesAction()) {
            r = gm.interact(mc.player, target, InteractionHand.MAIN_HAND);
        }
        return r.consumesAction();
    }

    /**
     * Use the held item while the player's rotation is temporarily set to (yaw, pitch).
     * The use-item packet carries the rotation, so the server aims the shot there, while the
     * local camera is restored before the next frame is rendered (camera never visibly moves).
     */
    static boolean useItemAimed(Minecraft mc, float yaw, float pitch) {
        LocalPlayer p = mc.player;
        float oy = p.getYRot(), ox = p.getXRot(), oyO = p.yRotO, oxO = p.xRotO;
        p.setYRot(yaw);
        p.setXRot(pitch);
        try {
            return mc.gameMode.useItem(p, InteractionHand.MAIN_HAND).consumesAction();
        } finally {
            p.setYRot(oy);
            p.setXRot(ox);
            p.yRotO = oyO;
            p.xRotO = oxO;
        }
    }

    static boolean lineOfSight(Minecraft mc, Vec3 target) {
        LocalPlayer p = mc.player;
        BlockHitResult r = mc.level.clip(new ClipContext(p.getEyePosition(), target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return r.getType() == HitResult.Type.MISS;
    }

    // --- TNT minecart fuse state, via reflection so a package move of the minecart classes cannot break the build ---
    private static final Map<Class<?>, Method> PRIMED = new ConcurrentHashMap<>();

    /** TRUE/FALSE if the cart reports its fuse state, null if it cannot be determined. */
    static Boolean isPrimed(Entity cart) {
        try {
            Method m = PRIMED.get(cart.getClass());
            if (m == null) {
                m = cart.getClass().getMethod("isPrimed");
                PRIMED.put(cart.getClass(), m);
            }
            return (Boolean) m.invoke(cart);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
