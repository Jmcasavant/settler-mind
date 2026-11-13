package dev.hearthkin.skill;

import dev.hearthkin.entity.SettlerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/** Walks a settler until a block is within reach. One instance per target. */
final class Approach {
    static final double REACH = 4.5;
    private static final int TIMEOUT = 20 * 20;

    private final BlockPos target;
    private int ticks;

    Approach(BlockPos target) {
        this.target = target;
    }

    static boolean inReach(SettlerEntity s, BlockPos pos) {
        return s.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= REACH * REACH;
    }

    /** @return null while walking, {@link Outcome#ok} on arrival, or a failure. */
    @Nullable Outcome tick(SettlerEntity s) {
        if (!s.getWorld().isChunkLoaded(target)) return Outcome.fail("chunk_unloaded", target.toShortString());
        if (inReach(s, target)) {
            s.getNavigation().stop();
            s.getLookControl().lookAt(Vec3d.ofCenter(target));
            return Outcome.ok("");
        }
        if (++ticks > TIMEOUT) return Outcome.fail("path_blocked", "could not reach " + target.toShortString());
        if (s.getNavigation().isIdle() && ticks % 20 == 1) {
            boolean started = s.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            if (!started && ticks > 40) return Outcome.fail("no_path", target.toShortString());
        }
        return null;
    }
}
