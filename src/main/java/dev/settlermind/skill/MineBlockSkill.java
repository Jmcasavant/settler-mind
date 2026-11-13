package dev.settlermind.skill;

import dev.settlermind.entity.BorrowedHands;
import dev.settlermind.entity.SettlerEntity;
import dev.settlermind.protection.ProtectionGuard;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Walk to a block, mine it with the best tool in the inventory, pick up the drops,
 * and succeed only if the inventory actually gained items. Addresses the classic
 * "block broke but nothing was collected" false success.
 */
public final class MineBlockSkill implements Skill {
    private static final int COLLECT_TIMEOUT = 20 * 4;

    private enum Phase { APPROACH, BREAK, COLLECT }

    private final BlockPos target;
    private final Approach approach;
    private Phase phase = Phase.APPROACH;
    private int phaseTicks;
    private float progress;
    private @Nullable BlockState expected;
    private Map<Item, Integer> before = Map.of();
    private int maxDropsSeen;

    public MineBlockSkill(BlockPos target) {
        this.target = target.toImmutable();
        this.approach = new Approach(this.target);
    }

    @Override public String name() { return "mine " + target.toShortString(); }
    @Override public String phase() { return phase.name().toLowerCase() + (phase == Phase.BREAK ? " " + Math.round(progress * 100) + "%" : ""); }

    @Override
    public @Nullable Outcome tick(SettlerEntity s) {
        ServerWorld world = (ServerWorld) s.getWorld();
        phaseTicks++;
        return switch (phase) {
            case APPROACH -> approach(s, world);
            case BREAK -> breakTick(s, world);
            case COLLECT -> collect(s, world);
        };
    }

    private @Nullable Outcome approach(SettlerEntity s, ServerWorld world) {
        if (!world.isChunkLoaded(target)) return Outcome.fail("chunk_unloaded", "");
        BlockState state = world.getBlockState(target);
        if (state.isAir()) return Outcome.fail("target_missing", "nothing to mine");
        if (state.getHardness(world, target) < 0) return Outcome.fail("unbreakable", id(state));
        Outcome walk = approach.tick(s);
        if (walk == null) return null;
        if (walk.status() != Outcome.Status.SUCCEEDED) return walk;
        String denied = BorrowedHands.with(s, fake -> ProtectionGuard.checkModify(world, fake, target));
        if (denied != null) return Outcome.fail(denied, target.toShortString());
        s.equipBestToolFor(state);
        expected = state;
        before = s.inventoryCounts();
        enter(Phase.BREAK);
        return null;
    }

    private @Nullable Outcome breakTick(SettlerEntity s, ServerWorld world) {
        BlockState now = world.getBlockState(target);
        if (expected == null || now != expected) {
            world.setBlockBreakingInfo(s.getId(), target, -1);
            return Outcome.fail("target_changed", "block changed while mining");
        }
        if (!Approach.inReach(s, target)) {
            world.setBlockBreakingInfo(s.getId(), target, -1);
            return Outcome.fail("out_of_reach", "pushed away while mining");
        }
        s.getLookControl().lookAt(Vec3d.ofCenter(target));
        if (phaseTicks % 5 == 1) s.swingHand(Hand.MAIN_HAND);

        float delta = BorrowedHands.with(s, fake -> now.calcBlockBreakingDelta(fake, world, target));
        if (delta <= 0) return Outcome.fail("unbreakable", id(now));
        progress += delta;
        world.setBlockBreakingInfo(s.getId(), target, Math.min(9, (int) (progress * 10)));
        if (progress < 1.0f) return null;

        world.setBlockBreakingInfo(s.getId(), target, -1);
        boolean broke = BorrowedHands.with(s, (FakePlayer fake) -> fake.interactionManager.tryBreakBlock(target));
        s.addExhaustion(0.005f);
        if (!broke || world.getBlockState(target) == expected) {
            return Outcome.fail("break_denied", "the game or another mod refused the break");
        }
        enter(Phase.COLLECT);
        return null;
    }

    private @Nullable Outcome collect(SettlerEntity s, ServerWorld world) {
        List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class,
                new Box(target).expand(4.0), e -> e.isAlive() && !e.getStack().isEmpty());
        maxDropsSeen = Math.max(maxDropsSeen, drops.size());
        if (!drops.isEmpty() && phaseTicks <= COLLECT_TIMEOUT) {
            ItemEntity nearest = drops.stream()
                    .min((a, b) -> Double.compare(a.squaredDistanceTo(s), b.squaredDistanceTo(s))).get();
            if (nearest.squaredDistanceTo(s) < 9) {
                // Pathfinding treats "within a block" as arrived, which can stop just outside
                // pickup range; for the last few blocks, steer straight onto the item.
                s.getNavigation().stop();
                s.getMoveControl().moveTo(nearest.getX(), nearest.getY(), nearest.getZ(), 1.0);
            } else if (phaseTicks % 10 == 1) {
                s.getNavigation().startMovingTo(nearest, 1.0);
            }
            return null;
        }
        Map<Item, Integer> gained = diff(before, s.inventoryCounts());
        if (gained.isEmpty()) {
            String why = maxDropsSeen == 0 ? "nothing dropped"
                    : drops.isEmpty() ? maxDropsSeen + " drop(s) vanished before pickup (taken by something else?)"
                    : "ran out of time reaching " + drops.size() + " drop(s)";
            return Outcome.partial("broken_not_collected", "block broke but no items reached the inventory: " + why);
        }
        return Outcome.ok(gained.entrySet().stream()
                .map(e -> "+" + e.getValue() + " " + Registries.ITEM.getId(e.getKey()))
                .collect(Collectors.joining(", ")));
    }

    @Override
    public Outcome cancel(SettlerEntity s) {
        s.getNavigation().stop();
        s.getWorld().setBlockBreakingInfo(s.getId(), target, -1);
        if (phase == Phase.COLLECT) return Outcome.partial("cancelled_after_break", "block already broken");
        return new Outcome(Outcome.Status.CANCELLED, "cancelled", "");
    }

    private void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private static String id(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    static Map<Item, Integer> diff(Map<Item, Integer> before, Map<Item, Integer> after) {
        return after.entrySet().stream()
                .filter(e -> e.getValue() > before.getOrDefault(e.getKey(), 0))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() - before.getOrDefault(e.getKey(), 0)));
    }
}
