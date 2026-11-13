package dev.settlermind.ai;

import dev.settlermind.entity.SettlerEntity;
import dev.settlermind.mind.Perception;
import dev.settlermind.mind.board.Board;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a bounded {@link Perception} on the server thread. Only loaded chunks, only
 * things in line of sight, capped counts: no omniscience and no tick-budget blowups.
 * Called only when a settler is about to think, never every tick.
 */
public final class PerceptionBuilder {
    private static final int BLOCK_RADIUS = 10;
    private static final int MAX_BLOCKS = 12;
    private static final int ENTITY_RADIUS = 20;
    private static final int MAX_ENTITIES = 10;

    private PerceptionBuilder() {}

    /** Board key for a block: "x,y,z" in the overworld, "dimension|x,y,z" elsewhere (one board per world save). */
    public static String posKey(World world, BlockPos pos) {
        String xyz = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        return world.getRegistryKey() == World.OVERWORLD ? xyz : world.getRegistryKey().getValue() + "|" + xyz;
    }

    public static Perception build(SettlerEntity s) {
        return build(s, null, 0, null);
    }

    /**
     * @param board       the settlement board (null: none), read at {@code boardNow} for views and reservations
     * @param noteVisible which tasks' notes this settler may see (the host's consent gate); null hides every note
     */
    public static Perception build(SettlerEntity s, @Nullable Board board, long boardNow,
                                   @Nullable java.util.function.Predicate<dev.settlermind.mind.board.BoardTask> noteVisible) {
        ServerWorld world = (ServerWorld) s.getWorld();
        BlockPos origin = s.getBlockPos();
        Vec3d eye = s.getEyePos();
        String selfId = s.getUuid().toString();

        List<Perception.SeenBlock> blocks = new ArrayList<>();
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -BLOCK_RADIUS; dx <= BLOCK_RADIUS; dx++) {
            for (int dy = -4; dy <= 6; dy++) {
                for (int dz = -BLOCK_RADIUS; dz <= BLOCK_RADIUS; dz++) {
                    p.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!world.isChunkLoaded(p)) continue;
                    BlockState state = world.getBlockState(p);
                    if (!notable(state)) continue;
                    if (!visible(world, s, eye, p)) continue;
                    // Only someone else's reservation is shown; ids are compared, names are for display.
                    String key = posKey(world, p);
                    String holder = board == null ? null : board.holderId(key, boardNow);
                    String reservedBy = holder == null || holder.equals(selfId) ? null : board.holderName(key, boardNow);
                    blocks.add(new Perception.SeenBlock(Registries.BLOCK.getId(state.getBlock()).getPath(),
                            p.getX(), p.getY(), p.getZ(), Math.sqrt(p.getSquaredDistance(eye)), reservedBy));
                }
            }
        }
        blocks.sort(Comparator.comparingDouble(Perception.SeenBlock::distance));
        if (blocks.size() > MAX_BLOCKS) blocks = new ArrayList<>(blocks.subList(0, MAX_BLOCKS));
        List<Perception.SeenBlock> seenBlocks = blocks;

        List<Perception.SeenEntity> entities = new ArrayList<>();
        for (Entity e : world.getOtherEntities(s, new Box(origin).expand(ENTITY_RADIUS), Entity::isAlive)) {
            if (!s.canSee(e)) continue;
            String kind;
            String name;
            if (e instanceof PlayerEntity pl) {
                if (pl.isSpectator() || pl.isInvisible()) continue;
                kind = "player";
                name = pl.getGameProfile().getName();
            } else if (e instanceof SettlerEntity other) {
                kind = "settler";
                name = other.getSettlerName();
            } else if (e instanceof Monster) {
                kind = "hostile";
                name = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            } else if (e instanceof AnimalEntity) {
                kind = "animal";
                name = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            } else if (e instanceof ItemEntity item) {
                kind = "item";
                name = item.getStack().getCount() + " " + Registries.ITEM.getId(item.getStack().getItem()).getPath();
            } else {
                continue;
            }
            boolean owner = e instanceof PlayerEntity pl && s.isOwner(pl);
            entities.add(new Perception.SeenEntity(kind, name, e.distanceTo(s), owner));
        }
        entities.sort(Comparator.comparingDouble(Perception.SeenEntity::distance));
        if (entities.size() > MAX_ENTITIES) entities = new ArrayList<>(entities.subList(0, MAX_ENTITIES));

        Map<String, Integer> inv = new LinkedHashMap<>();
        s.inventoryCounts().forEach((item, n) -> inv.put(Registries.ITEM.getId(item).getPath(), n));

        long time = world.getTimeOfDay() % 24000;
        String timeOfDay = time < 1000 ? "dawn" : time < 6000 ? "morning" : time < 11000 ? "afternoon"
                : time < 13000 ? "dusk" : time < 18000 ? "night" : "late night";
        String weather = world.isThundering() ? "thunderstorm" : world.isRaining() ? "raining" : "clear";
        String holding = s.getMainHandStack().isEmpty() ? "nothing" : Registries.ITEM.getId(s.getMainHandStack().getItem()).getPath();

        return new Perception(world.getTime(), timeOfDay, weather,
                new int[]{origin.getX(), origin.getY(), origin.getZ()}, s.getHealth(), s.getFoodLevel(), holding, inv,
                s.describeActivity(), List.copyOf(blocks), List.copyOf(entities),
                board == null ? List.of() : board.views(selfId, boardNow, item -> feasible(seenBlocks, item),
                        noteVisible == null ? t -> false : noteVisible)); // notes: fail closed without a gate
    }

    /** A task's item counts as feasible when a block of it is in sight. */
    private static boolean feasible(List<Perception.SeenBlock> blocks, String item) {
        for (Perception.SeenBlock b : blocks) if (Board.sameItem(b.id(), item)) return true;
        return false;
    }

    /** Blocks worth mentioning: resources, crops, workstations, storage, beds. */
    static boolean notable(BlockState state) {
        if (state.isAir()) return false;
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.BEDS) || state.isIn(BlockTags.CROPS)) return true;
        String id = Registries.BLOCK.getId(state.getBlock()).getPath();
        return id.endsWith("_ore") || id.equals("crafting_table") || id.equals("furnace") || id.equals("chest")
                || id.equals("barrel") || id.equals("pumpkin") || id.equals("melon") || id.equals("sugar_cane")
                || id.equals("hay_block");
    }

    private static boolean visible(ServerWorld world, SettlerEntity s, Vec3d eye, BlockPos pos) {
        Vec3d target = Vec3d.ofCenter(pos);
        var hit = world.raycast(new RaycastContext(eye, target, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, s));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }
}
