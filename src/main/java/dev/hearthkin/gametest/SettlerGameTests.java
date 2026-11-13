package dev.hearthkin.gametest;

import dev.hearthkin.Hearthkin;
import dev.hearthkin.ai.MindHost;
import dev.hearthkin.mind.Memory;
import dev.hearthkin.mind.Mind;
import dev.hearthkin.entity.SettlerEntity;
import dev.hearthkin.skill.MineBlockSkill;
import dev.hearthkin.skill.Outcome;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * In-world regression tests, run headless with {@code ./gradlew runGametest}.
 * Each one reproduces a failure mode called out in the design review.
 */
public final class SettlerGameTests implements FabricGameTest {
    private static final BlockPos START = new BlockPos(1, 1, 1);
    private static final BlockPos LOG = new BlockPos(4, 1, 4);

    private static void floor(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static SettlerEntity settler(TestContext ctx) {
        floor(ctx);
        SettlerEntity s = ctx.spawnEntity(Hearthkin.SETTLER, START);
        s.setSettlerName("Tess");
        s.setMode(SettlerEntity.Mode.STAY);
        return s;
    }

    private static void expectOutcome(SettlerEntity s, Outcome.Status status, String code) {
        Outcome o = s.getLastOutcome();
        if (o == null || s.getActiveSkill() != null) throw new GameTestException("skill still running");
        if (o.status() != status || !o.code().equals(code)) {
            throw new GameTestException("expected " + status + "(" + code + ") but got " + o);
        }
    }

    /**
     * Polls {@code check} every tick; completes the test the first tick it passes, and
     * fails with the check's own message if it still fails at {@code deadline}.
     */
    private static void until(TestContext ctx, int deadline, Runnable check) {
        poll(ctx, 1, deadline, check);
    }

    private static void poll(TestContext ctx, int tick, int deadline, Runnable check) {
        ctx.waitAndRun(1, () -> {
            try {
                check.run();
                ctx.complete();
            } catch (GameTestException e) {
                if (tick >= deadline) throw e;
                poll(ctx, tick + 1, deadline, check);
            }
        });
    }

    private static int count(SettlerEntity s, Item item) {
        return s.inventoryCounts().getOrDefault(item, 0);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void minesLogAndActuallyCollectsIt(TestContext ctx) {
        SettlerEntity s = settler(ctx);
        ctx.setBlockState(LOG, Blocks.OAK_LOG);
        s.startSkill(new MineBlockSkill(ctx.getAbsolutePos(LOG)));
        until(ctx, 380, () -> {
            expectOutcome(s, Outcome.Status.SUCCEEDED, "ok");
            ctx.expectBlock(Blocks.AIR, LOG);
            if (count(s, Items.OAK_LOG) != 1) throw new GameTestException("expected exactly 1 oak log, got " + count(s, Items.OAK_LOG));
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void usesBestToolFromInventory(TestContext ctx) {
        SettlerEntity s = settler(ctx);
        ctx.setBlockState(LOG, Blocks.STONE);
        s.getInventory().addStack(new ItemStack(Items.DIRT, 5));
        s.getInventory().addStack(new ItemStack(Items.WOODEN_PICKAXE));
        s.startSkill(new MineBlockSkill(ctx.getAbsolutePos(LOG)));
        until(ctx, 380, () -> {
            expectOutcome(s, Outcome.Status.SUCCEEDED, "ok");
            if (count(s, Items.COBBLESTONE) != 1) throw new GameTestException("expected cobblestone from pickaxe mining");
            if (!s.getMainHandStack().isOf(Items.WOODEN_PICKAXE) || s.getMainHandStack().getDamage() != 1) {
                throw new GameTestException("pickaxe should be in hand with 1 durability used, got " + s.getMainHandStack());
            }
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void stoneByHandIsBrokenButYieldsNothing(TestContext ctx) {
        // Without a pickaxe stone drops nothing: must be PARTIAL, never a false success.
        SettlerEntity s = settler(ctx);
        ctx.setBlockState(LOG, Blocks.STONE);
        s.startSkill(new MineBlockSkill(ctx.getAbsolutePos(LOG)));
        until(ctx, 380, () -> expectOutcome(s, Outcome.Status.PARTIAL, "broken_not_collected"));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void refusesUnbreakableBlock(TestContext ctx) {
        SettlerEntity s = settler(ctx);
        ctx.setBlockState(LOG, Blocks.BEDROCK);
        s.startSkill(new MineBlockSkill(ctx.getAbsolutePos(LOG)));
        until(ctx, 180, () -> {
            expectOutcome(s, Outcome.Status.FAILED, "unbreakable");
            ctx.expectBlock(Blocks.BEDROCK, LOG);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300)
    public void blockReplacedWhileMiningIsDetected(TestContext ctx) {
        SettlerEntity s = settler(ctx);
        BlockPos near = new BlockPos(2, 1, 1); // already in reach: mining starts immediately
        ctx.setBlockState(near, Blocks.OAK_LOG);
        s.startSkill(new MineBlockSkill(ctx.getAbsolutePos(near)));
        ctx.waitAndRun(15, () -> ctx.setBlockState(near, Blocks.BIRCH_LOG));
        until(ctx, 280, () -> {
            expectOutcome(s, Outcome.Status.FAILED, "target_changed");
            ctx.expectBlock(Blocks.BIRCH_LOG, near);
            if (!s.inventoryCounts().isEmpty()) throw new GameTestException("no items should be gained");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void eatsWhenHungryAndSkipsHarmfulFood(TestContext ctx) {
        SettlerEntity s = settler(ctx);
        s.getInventory().addStack(new ItemStack(Items.ROTTEN_FLESH, 3));
        s.getInventory().addStack(new ItemStack(Items.BREAD, 2));
        s.setFoodLevel(10);
        until(ctx, 180, () -> {
            if (s.getFoodLevel() < 15) throw new GameTestException("food still " + s.getFoodLevel());
            if (count(s, Items.BREAD) != 1) throw new GameTestException("should eat exactly one bread");
            if (count(s, Items.ROTTEN_FLESH) != 3) throw new GameTestException("should not eat rotten flesh when not starving");
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void survivesSaveAndLoadWithoutDuplication(TestContext ctx) {
        SettlerEntity s = settler(ctx);
        s.getInventory().addStack(new ItemStack(Items.OAK_LOG, 7));
        s.setFoodLevel(11);
        ctx.setBlockState(LOG, Blocks.OAK_LOG);
        s.startSkill(new MineBlockSkill(ctx.getAbsolutePos(LOG)));
        NbtCompound saved = s.writeNbt(new NbtCompound());

        SettlerEntity loaded = Hearthkin.SETTLER.create(ctx.getWorld());
        if (loaded == null) throw new GameTestException("could not create settler");
        loaded.readNbt(saved); // not spawned: a second live copy would be a duplicate
        if (!loaded.getUuid().equals(s.getUuid())) throw new GameTestException("identity changed");
        if (!"Tess".equals(loaded.getSettlerName())) throw new GameTestException("name lost");
        if (loaded.getFoodLevel() != 11) throw new GameTestException("food lost");
        if (count(loaded, Items.OAK_LOG) != 7) throw new GameTestException("inventory changed: " + loaded.inventoryCounts());
        if (loaded.getLastOutcome() == null || loaded.getLastOutcome().status() != Outcome.Status.UNCERTAIN) {
            throw new GameTestException("in-flight skill must reload as UNCERTAIN, got " + loaded.getLastOutcome());
        }
        loaded.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void dismissDropsEverythingExactlyOnce(TestContext ctx) {
        SettlerEntity s = settler(ctx);
        s.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 20));
        s.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_AXE));
        s.dismiss();
        ctx.waitAndRun(2, () -> {
            int cobble = 0, axes = 0;
            for (ItemEntity e : ctx.getWorld().getEntitiesByClass(ItemEntity.class,
                    new Box(ctx.getAbsolutePos(BlockPos.ORIGIN)).expand(10), e -> true)) {
                if (e.getStack().isOf(Items.COBBLESTONE)) cobble += e.getStack().getCount();
                if (e.getStack().isOf(Items.STONE_AXE)) axes += e.getStack().getCount();
            }
            if (cobble != 20 || axes != 1) throw new GameTestException("dropped " + cobble + " cobble, " + axes + " axes");
            if (s.isAlive()) throw new GameTestException("settler should be gone");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 500)
    public void hearsRequestDecidesActsAndRemembers(TestContext ctx) {
        // Full loop with the offline mind: hear -> think -> validated plan -> skill -> verified outcome -> memory.
        SettlerEntity s = settler(ctx);
        ctx.setBlockState(LOG, Blocks.OAK_LOG);
        MindHost host = MindHost.get();
        if (host == null) throw new GameTestException("mind host not running");
        Mind mind = host.mindFor(s);
        mind.setOwnerName("Alex");
        host.hear(s, "Alex", "player", "Tess, could you chop that log please?", true, true);
        until(ctx, 480, () -> {
            ctx.expectBlock(Blocks.AIR, LOG);
            if (count(s, Items.OAK_LOG) != 1) throw new GameTestException("log not collected");
            boolean heard = mind.memory().all().stream().anyMatch(m -> m.kind() == Memory.Kind.DIALOGUE && m.text().contains("Alex said"));
            boolean decided = mind.memory().all().stream().anyMatch(m -> m.kind() == Memory.Kind.DECISION && m.text().contains("mine the block"));
            boolean did = mind.memory().all().stream().anyMatch(m -> m.kind() == Memory.Kind.EVENT && m.text().contains("succeeded"));
            if (!heard || !decided || !did) throw new GameTestException("memory incomplete: heard=" + heard + " decided=" + decided + " did=" + did);
            if (mind.relationships().get("Alex").affinity <= 0.35) throw new GameTestException("politeness not noticed");
        });
    }
}
