package dev.autocart;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Runs the whole TNT minecart sequence as a state machine, one step per client tick.
 * Every "place/use" stage is followed by a VERIFY stage that checks real, server-confirmed
 * world/inventory state before the next stage is allowed to start.
 */
final class AutoCartController {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoCart");

    enum Stage {
        IDLE("Idle"),
        PREPARE("Preparing items"),
        PREPARE_VERIFY("Verifying inventory"),
        PLACE_RAIL("Placing rail"),
        VERIFY_RAIL("Verifying rail"),
        PLACE_CART("Placing TNT minecart"),
        VERIFY_CART("Verifying minecart"),
        MOVE_BEHIND("Moving behind minecart"),
        IGNITE("Igniting with flint and steel"),
        VERIFY_IGNITE("Verifying ignition"),
        FIRE("Firing crossbow"),
        VERIFY_FIRE("Verifying shot");

        final String label;
        Stage(String label) { this.label = label; }
    }

    private static final Predicate<ItemStack> RAIL = s -> s.is(Items.RAIL);
    private static final Predicate<ItemStack> CART = s -> s.is(Items.TNT_MINECART);
    private static final Predicate<ItemStack> FLINT = s -> s.is(Items.FLINT_AND_STEEL);
    private static final Predicate<ItemStack> CROSSBOW = s -> s.is(Items.CROSSBOW) && McCompat.isCharged(s);

    private final AutoCartConfig cfg;
    private final InventoryManager inv = new InventoryManager();

    private boolean enabled;
    private Stage stage = Stage.IDLE;
    private int wait;        // ticks still to wait before running the current stage
    private int stageTicks;  // ticks spent in the current stage (including waiting)
    private int overlayTimer;
    private int holdTicks;   // keeps the final OFF/result message on screen for a moment
    private String finalMsg;

    // sequence data
    private int railSlot, cartSlot, flintSlot, crossbowSlot;
    private RailPlacement.Spot spot;
    private Set<Integer> preexistingCarts = Set.of();
    private Entity cart;
    private Vec3 lastPos;
    private int stuckTicks;
    private boolean warnedUnknownFuse;

    AutoCartController(AutoCartConfig cfg) { this.cfg = cfg; }

    // ------------------------------------------------------------------ control

    void toggle(Minecraft mc) {
        if (enabled) {
            end(mc, "Cancelled");
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return;
        if (McCompat.screenOpen(mc)) return;
        finalMsg = null;
        inv.begin(p);
        enabled = true;
        go(Stage.PREPARE, 0);
        show(mc);
    }

    void tick(Minecraft mc) {
        if (!enabled) {
            if (holdTicks > 0 && --holdTicks % 10 == 0 && mc.player != null) show(mc);
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            enabled = false;
            stage = Stage.IDLE;
            return;
        }
        if (p.isDeadOrDying()) { fail(mc, "player died"); return; }

        if (++overlayTimer >= 5) { overlayTimer = 0; show(mc); }

        stageTicks++;
        if (stageTicks > cfg.stageTimeoutTicks + McCompat.pingTicks(mc) * 2) {
            fail(mc, "timed out during: " + stage.label);
            return;
        }
        if (wait > 0) { wait--; return; }
        if (McCompat.screenOpen(mc)) return; // paused while a GUI is open (timeout still counts)

        switch (stage) {
            case PREPARE -> prepare(mc);
            case PREPARE_VERIFY -> prepareVerify(mc);
            case PLACE_RAIL -> placeRail(mc);
            case VERIFY_RAIL -> verifyRail(mc);
            case PLACE_CART -> placeCart(mc);
            case VERIFY_CART -> verifyCart(mc);
            case MOVE_BEHIND -> moveBehind(mc);
            case IGNITE -> ignite(mc);
            case VERIFY_IGNITE -> verifyIgnite(mc);
            case FIRE -> fire(mc);
            case VERIFY_FIRE -> verifyFire(mc);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ stages

    private void prepare(Minecraft mc) {
        Set<Integer> reserved = new HashSet<>();
        if ((railSlot = take(mc, RAIL, reserved, "Rail")) < 0) return;
        if ((cartSlot = take(mc, CART, reserved, "TNT Minecart")) < 0) return;
        if ((flintSlot = take(mc, FLINT, reserved, "Flint and Steel")) < 0) return;
        if ((crossbowSlot = take(mc, CROSSBOW, reserved, "charged Crossbow")) < 0) return;
        go(Stage.PREPARE_VERIFY, verifyWait(mc));
    }

    /** @return hotbar slot, or -1 after failing the sequence (item missing / no free slot). */
    private int take(Minecraft mc, Predicate<ItemStack> match, Set<Integer> reserved, String name) {
        int slot = inv.ensureInHotbar(mc, match, reserved);
        if (slot < 0) {
            fail(mc, "missing item: " + name);
            return -1;
        }
        reserved.add(slot);
        return slot;
    }

    private void prepareVerify(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!RAIL.test(p.getInventory().getItem(railSlot))
                || !CART.test(p.getInventory().getItem(cartSlot))
                || !FLINT.test(p.getInventory().getItem(flintSlot))
                || !CROSSBOW.test(p.getInventory().getItem(crossbowSlot))) {
            fail(mc, "inventory swap was not accepted by the server");
            return;
        }
        go(Stage.PLACE_RAIL, 0);
    }

    private void placeRail(Minecraft mc) {
        if (!hold(mc, railSlot, RAIL)) { fail(mc, "could not select rail"); return; }
        Optional<RailPlacement.Spot> found = RailPlacement.find(mc, cfg);
        if (found.isEmpty()) { fail(mc, "no valid rail position in front of you"); return; }
        spot = found.get();
        if (!McCompat.useItemOn(mc, spot.placeHit())) { fail(mc, "rail placement refused"); return; }
        go(Stage.VERIFY_RAIL, verifyWait(mc));
    }

    private void verifyRail(Minecraft mc) {
        // The client predicts the block instantly; we only look after waiting for the server's correction window.
        BlockState state = mc.level.getBlockState(spot.rail());
        if (!state.is(BlockTags.RAILS)) { fail(mc, "rail was not placed (rejected by server)"); return; }
        go(Stage.PLACE_CART, cfg.stepDelayTicks);
    }

    private void placeCart(Minecraft mc) {
        if (!mc.level.getBlockState(spot.rail()).is(BlockTags.RAILS)) { fail(mc, "rail disappeared"); return; }
        if (!hold(mc, cartSlot, CART)) { fail(mc, "could not select TNT minecart"); return; }
        preexistingCarts = new HashSet<>();
        for (Entity e : cartsNear(mc)) preexistingCarts.add(e.getId());
        if (!McCompat.useItemOn(mc, spot.cartHit())) { fail(mc, "minecart placement refused"); return; }
        go(Stage.VERIFY_CART, 0);
    }

    private void verifyCart(Minecraft mc) {
        for (Entity e : cartsNear(mc)) {
            if (!preexistingCarts.contains(e.getId())) {
                cart = e;
                warnedUnknownFuse = false;
                lastPos = null;
                stuckTicks = 0;
                go(Stage.MOVE_BEHIND, cfg.stepDelayTicks);
                return;
            }
        }
        if (stageTicks > cfg.verifyTimeoutTicks + McCompat.pingTicks(mc) * 2) {
            fail(mc, "TNT minecart did not appear (placement rejected)");
        }
    }

    private void moveBehind(Minecraft mc) {
        if (!cartAlive()) { fail(mc, "minecart vanished"); return; }
        LocalPlayer p = mc.player;
        Vec3 railC = spot.railCenter();
        double fx = spot.facing().getStepX(), fz = spot.facing().getStepZ();

        // Position relative to the rail centre, split into axial (along the rail) and lateral parts.
        double vx = p.getX() - railC.x, vz = p.getZ() - railC.z;
        double axial = -(vx * fx + vz * fz);          // > 0 means "behind" the cart
        double lateral = Math.abs(vx * fz - vz * fx);
        double maxAxial = McCompat.entityRange(p) - 0.4;
        boolean behind = axial >= 1.4 && axial <= maxAxial && lateral <= 0.6;

        if (behind) {
            go(Stage.IGNITE, cfg.stepDelayTicks);
            return;
        }

        Vec3 target = new Vec3(railC.x - fx * cfg.standDistance, p.getY(), railC.z - fz * cfg.standDistance);
        double dx = target.x - p.getX(), dz = target.z - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.15) { go(Stage.IGNITE, cfg.stepDelayTicks); return; }

        // Normal client-side movement (the client sends its usual position packets); camera is untouched.
        double s = Math.min(cfg.walkSpeed, dist);
        p.setDeltaMovement(dx / dist * s, p.getDeltaMovement().y, dz / dist * s);

        Vec3 now = p.position();
        if (lastPos != null && now.distanceToSqr(lastPos) < 0.0004) stuckTicks++; else stuckTicks = 0;
        lastPos = now;
        if (stuckTicks > 10) fail(mc, "cannot reach a spot behind the minecart (blocked)");
    }

    private void ignite(Minecraft mc) {
        if (!cartAlive()) { fail(mc, "minecart vanished"); return; }
        if (!hold(mc, flintSlot, FLINT)) { fail(mc, "could not select flint and steel"); return; }
        if (!inReach(mc)) { fail(mc, "minecart out of reach"); return; }
        Vec3 aim = cart.getBoundingBox().getCenter();
        if (!McCompat.lineOfSight(mc, aim)) { fail(mc, "no line of sight to minecart"); return; }
        if (!McCompat.interactEntity(mc, cart, aim.subtract(cart.position()))) {
            fail(mc, "ignition interaction refused");
            return;
        }
        go(Stage.VERIFY_IGNITE, verifyWait(mc));
    }

    private void verifyIgnite(Minecraft mc) {
        if (!cartAlive()) { fail(mc, "minecart vanished before ignition was confirmed"); return; }
        Boolean primed = McCompat.isPrimed(cart);
        if (primed == null) {
            // Cannot read the fuse on this version: continue, but tell the user once.
            if (!warnedUnknownFuse) {
                warnedUnknownFuse = true;
                LOGGER.warn("Cannot read TNT minecart fuse state; ignition is not verifiable on this version.");
            }
            go(Stage.FIRE, cfg.fireDelayTicks);
            return;
        }
        if (primed) { go(Stage.FIRE, cfg.fireDelayTicks); return; }
        if (stageTicks > cfg.verifyTimeoutTicks + McCompat.pingTicks(mc) * 2) {
            fail(mc, "minecart did not ignite");
        }
    }

    private void fire(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (!cartAlive()) { fail(mc, "minecart vanished"); return; }
        if (!hold(mc, crossbowSlot, CROSSBOW)) { fail(mc, "charged crossbow not selectable"); return; }
        Vec3 aim = cart.getBoundingBox().getCenter();
        if (!McCompat.lineOfSight(mc, aim)) { fail(mc, "no line of sight for the shot"); return; }

        Vec3 d = aim.subtract(p.getEyePosition());
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, horiz));

        if (!McCompat.useItemAimed(mc, yaw, pitch)) { fail(mc, "crossbow use refused"); return; }
        go(Stage.VERIFY_FIRE, verifyWait(mc));
    }

    private void verifyFire(Minecraft mc) {
        if (!McCompat.isCharged(mc.player.getInventory().getItem(crossbowSlot))) {
            end(mc, "Sequence complete");
            return;
        }
        if (stageTicks > cfg.verifyTimeoutTicks + McCompat.pingTicks(mc) * 2) {
            fail(mc, "crossbow did not fire");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void go(Stage next, int waitTicks) {
        stage = next;
        wait = Math.max(0, waitTicks);
        stageTicks = 0;
    }

    private int verifyWait(Minecraft mc) { return cfg.verifyDelayTicks + McCompat.pingTicks(mc); }

    /** Select a hotbar slot and confirm the main hand really holds the expected item. */
    private boolean hold(Minecraft mc, int slot, Predicate<ItemStack> match) {
        McCompat.selectSlot(mc.player, slot);
        return match.test(mc.player.getMainHandItem());
    }

    private boolean cartAlive() { return cart != null && cart.isAlive() && !cart.isRemoved(); }

    private boolean inReach(Minecraft mc) {
        double r = McCompat.entityRange(mc.player) - 0.2;
        return cart.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) <= r * r;
    }

    private List<Entity> cartsNear(Minecraft mc) {
        AABB box = new AABB(spot.rail()).inflate(0.5);
        return mc.level.getEntities((Entity) null, box, e -> e.getType() == EntityType.TNT_MINECART);
    }

    private void fail(Minecraft mc, String reason) {
        LOGGER.warn("Sequence aborted at {}: {}", stage, reason);
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[Auto Cart] Aborted: " + reason));
        end(mc, "FAILED - " + reason);
    }

    /** Stops the sequence (success, failure or cancel), restores the inventory and switches to OFF. */
    private void end(Minecraft mc, String message) {
        if (cfg.restoreInventory) inv.restore(mc);
        enabled = false;
        stage = Stage.IDLE;
        cart = null;
        spot = null;
        finalMsg = message;
        holdTicks = 60;
        if (mc.player != null) show(mc);
    }

    private void show(Minecraft mc) {
        String text = enabled
                ? "Auto Cart: ON - " + stage.label
                : "Auto Cart: OFF" + (finalMsg == null ? "" : " - " + finalMsg);
        McCompat.overlay(mc, Component.literal(text));
    }
}
