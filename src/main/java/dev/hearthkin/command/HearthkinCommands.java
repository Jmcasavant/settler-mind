package dev.hearthkin.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.hearthkin.Hearthkin;
import dev.hearthkin.ai.MindHost;
import dev.hearthkin.mind.Memory;
import dev.hearthkin.mind.Mind;
import dev.hearthkin.mind.Relationships;
import dev.hearthkin.entity.SettlerEntity;
import dev.hearthkin.protection.ProtectionGuard;
import dev.hearthkin.skill.MineBlockSkill;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /hk summon|list|status|follow|stay|mine|pause|resume|dismiss.
 * Only a settler's owner (or an operator) may control it.
 */
public final class HearthkinCommands {
    private static final int MAX_SETTLERS_PER_OWNER = 4;
    private static final SimpleCommandExceptionType NOT_FOUND =
            new SimpleCommandExceptionType(Text.literal("No settler with that name that you own is loaded."));

    private HearthkinCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        LiteralArgumentBuilder<ServerCommandSource> root = literal("hk");

        root.then(literal("summon").then(argument("name", StringArgumentType.word()).executes(HearthkinCommands::summon)));
        root.then(literal("list").executes(HearthkinCommands::list));
        root.then(named("status", (c, s) -> status(c.getSource(), s)));
        root.then(named("follow", (c, s) -> { s.setMode(SettlerEntity.Mode.FOLLOW); ok(c, s, "following you"); }));
        root.then(named("stay", (c, s) -> { s.setMode(SettlerEntity.Mode.STAY); ok(c, s, "staying here"); }));
        root.then(named("pause", (c, s) -> { s.setPaused(true); ok(c, s, "paused"); }));
        root.then(named("resume", (c, s) -> { s.setPaused(false); ok(c, s, "resumed"); }));
        root.then(named("stop", (c, s) -> { s.cancelSkill(); ok(c, s, "stopped: " + s.getLastOutcome()); }));
        root.then(named("dismiss", (c, s) -> { String n = s.getSettlerName(); s.dismiss(); c.getSource().sendFeedback(() -> Text.literal(n + " dismissed; carried items dropped."), false); }));
        root.then(literal("mine").then(argument("name", StringArgumentType.word())
                .then(argument("pos", BlockPosArgumentType.blockPos()).executes(c -> {
                    SettlerEntity s = find(c);
                    BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(c, "pos");
                    s.startSkill(new MineBlockSkill(pos));
                    ok(c, s, "heading to mine " + pos.toShortString());
                    return 1;
                }))));

        root.then(named("mind", (c, s) -> mind(c.getSource(), s)));
        root.then(literal("usage").requires(src -> src.hasPermissionLevel(2)).executes(HearthkinCommands::usage));
        root.then(literal("consent")
                .then(literal("on").executes(c -> consent(c, true)))
                .then(literal("off").executes(c -> consent(c, false)))
                .executes(c -> consent(c, null)));

        d.register(root);
    }

    private interface SettlerAction { void run(CommandContext<ServerCommandSource> c, SettlerEntity s) throws CommandSyntaxException; }

    private static LiteralArgumentBuilder<ServerCommandSource> named(String verb, SettlerAction action) {
        return literal(verb).then(argument("name", StringArgumentType.word()).executes(c -> {
            action.run(c, find(c));
            return 1;
        }));
    }

    private static int summon(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity player = c.getSource().getPlayerOrThrow();
        String name = StringArgumentType.getString(c, "name");
        if (name.length() > 16) throw new SimpleCommandExceptionType(Text.literal("Name must be 16 characters or fewer.")).create();
        List<SettlerEntity> mine = new ArrayList<>();
        forEachSettler(c.getSource(), s -> { if (s.isOwner(player)) mine.add(s); });
        if (mine.stream().anyMatch(s -> s.getSettlerName().equalsIgnoreCase(name))) {
            throw new SimpleCommandExceptionType(Text.literal("You already have a settler called " + name + ".")).create();
        }
        if (mine.size() >= MAX_SETTLERS_PER_OWNER && !c.getSource().hasPermissionLevel(2)) {
            throw new SimpleCommandExceptionType(Text.literal("Settler limit reached (" + MAX_SETTLERS_PER_OWNER + ").")).create();
        }
        ServerWorld world = player.getServerWorld();
        SettlerEntity settler = Hearthkin.SETTLER.create(world);
        if (settler == null) return 0;
        settler.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), 0);
        settler.initialize(world, world.getLocalDifficulty(player.getBlockPos()), SpawnReason.COMMAND, null, null);
        settler.setOwner(player);
        settler.setSettlerName(name);
        world.spawnEntity(settler);
        c.getSource().sendFeedback(() -> Text.literal(name + " has arrived. Talk to them in chat using their name. "
                + "/hk mind " + name + " shows what they think and remember. Sneak + right-click: inventory."), false);
        String claim = ProtectionGuard.unsupportedClaimMod();
        if (claim != null) {
            c.getSource().sendFeedback(() -> Text.literal("Note: claims mod '" + claim + "' detected and not yet supported, so " + name + " will refuse to change blocks."), false);
        }
        return 1;
    }

    private static int list(CommandContext<ServerCommandSource> c) {
        List<String> lines = new ArrayList<>();
        forEachSettler(c.getSource(), s -> {
            if (canControl(c.getSource(), s)) lines.add(s.getSettlerName() + " @ " + s.getBlockPos().toShortString());
        });
        c.getSource().sendFeedback(() -> Text.literal(lines.isEmpty() ? "No loaded settlers." : "Settlers: " + String.join(", ", lines)), false);
        return lines.size();
    }

    private static void status(ServerCommandSource src, SettlerEntity s) {
        StringBuilder inv = new StringBuilder();
        s.inventoryCounts().forEach((item, n) -> inv.append(n).append(' ').append(Registries.ITEM.getId(item).getPath()).append(", "));
        ItemStack hand = s.getMainHandStack();
        String text = s.getSettlerName()
                + " | hp " + Math.round(s.getHealth()) + "/" + Math.round(s.getMaxHealth())
                + " | food " + s.getFoodLevel() + "/20" + (s.isEating() ? " (eating)" : "")
                + " | " + (s.isPaused() ? "PAUSED" : s.getMode().name().toLowerCase())
                + "\n doing: " + (s.getActiveSkill() == null ? "nothing" : s.getActiveSkill().name() + " [" + s.getActiveSkill().phase() + "]")
                + "\n last: " + (s.getLastOutcome() == null ? "-" : s.getLastOutcome())
                + "\n hand: " + (hand.isEmpty() ? "empty" : Registries.ITEM.getId(hand.getItem()).getPath())
                + " | carrying: " + (inv.length() == 0 ? "nothing" : inv.substring(0, inv.length() - 2));
        src.sendFeedback(() -> Text.literal(text), false);
    }

    private static void mind(ServerCommandSource src, SettlerEntity s) {
        MindHost host = MindHost.get();
        if (host == null) return;
        Mind m = host.mindFor(s);
        long now = s.getWorld().getTime();
        StringBuilder sb = new StringBuilder();
        sb.append("== ").append(m.persona().name()).append(" ==\n").append(m.persona().describe()).append('\n');
        sb.append("Mood: ").append(m.mood()).append(" | Needs: ").append(m.needs().describe()).append('\n');
        sb.append("Summary: ").append(m.summary().isBlank() ? "-" : m.summary()).append('\n');
        if (!m.intentions().isEmpty()) sb.append("Today: ").append(String.join("; ", m.intentions())).append('\n');
        if (m.lastDecision() != null && !m.lastDecision().thought().isBlank()) sb.append("Last thought: ").append(m.lastDecision().thought()).append('\n');
        for (Relationships.Bond b : m.relationships().all().values()) sb.append("- ").append(b.name).append(": ").append(b.describe()).append('\n');
        sb.append("Lessons:\n").append(m.lessons().render());
        sb.append("Memories (").append(m.memory().size()).append(", latest 5):\n");
        for (Memory mem : m.memory().recent(5)) sb.append("- ").append(mem.render(now)).append('\n');
        if (m.lastError() != null) sb.append("Last problem: ").append(m.lastError());
        String text = sb.toString();
        src.sendFeedback(() -> Text.literal(text), false);
    }

    private static int usage(CommandContext<ServerCommandSource> c) {
        MindHost host = MindHost.get();
        if (host == null) return 0;
        StringBuilder sb = new StringBuilder("AI usage since server start (estimates):\n");
        host.ledger().snapshot().forEach((k, v) -> sb.append(k).append(": ").append(v.summary()).append('\n'));
        sb.append(String.format(java.util.Locale.ROOT, "Total ~$%.4f", host.ledger().totalUsd()));
        String text = sb.toString();
        c.getSource().sendFeedback(() -> Text.literal(text), false);
        return 1;
    }

    private static int consent(CommandContext<ServerCommandSource> c, Boolean on) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        MindHost host = MindHost.get();
        if (host == null) return 0;
        if (on != null) host.setConsent(p.getUuid(), on);
        boolean now = host.hasConsent(p.getUuid());
        boolean remote = host.config().anyRemoteProvider();
        String msg = (now ? "Settlers' AI may hear your chat." : "Settlers hear that you spoke, but not your words.")
                + (remote ? " (Your words are sent to the configured AI provider only if you opt in: /hk consent on|off)"
                          : " (No AI provider is configured; nothing leaves this server.)");
        c.getSource().sendFeedback(() -> Text.literal(msg), false);
        return 1;
    }

    private static void ok(CommandContext<ServerCommandSource> c, SettlerEntity s, String msg) {
        c.getSource().sendFeedback(() -> Text.literal(s.getSettlerName() + ": " + msg), false);
    }

    private static SettlerEntity find(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "name");
        SettlerEntity[] found = new SettlerEntity[1];
        forEachSettler(c.getSource(), s -> {
            if (found[0] == null && s.getSettlerName().equalsIgnoreCase(name) && canControl(c.getSource(), s)) found[0] = s;
        });
        if (found[0] == null) throw NOT_FOUND.create();
        return found[0];
    }

    private static boolean canControl(ServerCommandSource src, SettlerEntity s) {
        if (src.hasPermissionLevel(2)) return true;
        ServerPlayerEntity p = src.getPlayer();
        return p != null && s.isOwner(p);
    }

    private static void forEachSettler(ServerCommandSource src, Consumer<SettlerEntity> fn) {
        for (ServerWorld world : src.getServer().getWorlds()) {
            world.getEntitiesByType(Hearthkin.SETTLER, e -> true).forEach(fn);
        }
    }
}
