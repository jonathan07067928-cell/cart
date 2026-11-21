package dev.autocart;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** Finds and validates a rail position in front of the player. */
final class RailPlacement {
    private RailPlacement() {}

    record Spot(BlockPos support, BlockPos rail, Direction facing) {
        Vec3 railCenter() { return new Vec3(rail.getX() + 0.5, rail.getY(), rail.getZ() + 0.5); }

        /** Click point on the top face of the supporting block (where the rail gets placed). */
        BlockHitResult placeHit() {
            return new BlockHitResult(new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5),
                    Direction.UP, support, false);
        }

        /** Click point on the rail itself (used to place the minecart). */
        BlockHitResult cartHit() {
            return new BlockHitResult(new Vec3(rail.getX() + 0.5, rail.getY() + 0.125, rail.getZ() + 0.5),
                    Direction.UP, rail, false);
        }
    }

    static Optional<Spot> find(Minecraft mc, AutoCartConfig cfg) {
        LocalPlayer p = mc.player;
        Direction facing = p.getDirection();
        BlockPos base = p.blockPosition();
        double reach = McCompat.blockRange(p) - 0.3;
        Vec3 eye = p.getEyePosition();
        int[] dys = {0, -1, 1};

        for (int dist = cfg.railDistanceMin; dist <= cfg.railDistanceMax; dist++) {
            for (int dy : dys) {
                BlockPos rail = base.relative(facing, dist).offset(0, dy, 0);
                BlockPos support = rail.below();

                BlockState railState = mc.level.getBlockState(rail);
                if (!railState.canBeReplaced() || !railState.getFluidState().isEmpty()) continue;
                if (!mc.level.getBlockState(support).isFaceSturdy(mc.level, support, Direction.UP)) continue;

                Spot spot = new Spot(support, rail, facing);
                Vec3 hit = spot.placeHit().getLocation();
                if (eye.distanceTo(hit) > reach) continue;

                // Nothing may block the view of the top face.
                BlockHitResult clip = mc.level.clip(new ClipContext(eye, hit.add(0, 0.001, 0),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                if (clip.getType() != HitResult.Type.MISS) continue;

                return Optional.of(spot);
            }
        }
        return Optional.empty();
    }
}
