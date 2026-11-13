package dev.hearthkin.skill;

import dev.hearthkin.entity.SettlerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Walk to a spot. Succeeds only when the settler is actually there. */
public final class GoToSkill implements Skill {
    private static final double ARRIVE = 2.0;
    private static final int TIMEOUT = 20 * 30;

    private final BlockPos target;
    private int ticks;

    public GoToSkill(BlockPos target) {
        this.target = target.toImmutable();
    }

    @Override public String name() { return "go to " + target.toShortString(); }
    @Override public String phase() { return "walking"; }

    @Override
    public @Nullable Outcome tick(SettlerEntity s) {
        if (!s.getWorld().isChunkLoaded(target)) return Outcome.fail("chunk_unloaded", target.toShortString());
        if (s.getBlockPos().getSquaredDistance(target) <= ARRIVE * ARRIVE) {
            s.getNavigation().stop();
            return Outcome.ok("arrived at " + target.toShortString());
        }
        if (++ticks > TIMEOUT) return Outcome.fail("path_blocked", "could not reach " + target.toShortString());
        if (s.getNavigation().isIdle() && ticks % 20 == 1) {
            boolean started = s.getNavigation().startMovingTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            if (!started && ticks > 40) return Outcome.fail("no_path", target.toShortString());
        }
        return null;
    }

    @Override
    public Outcome cancel(SettlerEntity s) {
        s.getNavigation().stop();
        return new Outcome(Outcome.Status.CANCELLED, "cancelled", "");
    }
}
