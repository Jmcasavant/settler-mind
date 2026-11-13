package dev.settlermind.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.settlermind.SettlerMind;
import dev.settlermind.ai.MindHost;
import dev.settlermind.mind.Memory;
import dev.settlermind.mind.Mind;
import dev.settlermind.mind.Relationships;
import dev.settlermind.mind.board.Board;
import dev.settlermind.mind.board.BoardTask;
import dev.settlermind.entity.SettlerEntity;
import dev.settlermind.protection.ProtectionGuard;
import dev.settlermind.skill.MineBlockSkill;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /settler (alias /sm) summon|list|status|follow|stay|mine|pause|resume|dismiss, plus the settlement
 * board: board, task post|cancel. Only a settler's owner (or an operator) may control it; anyone may
 * view the board and post; only the poster (or an operator) may cancel a task.
 */
public final class SettlerCommands {
    private static final int MAX_SETTLERS_PER_OWNER = 4;
    private static final SimpleCommandExceptionType NOT_FOUND =
            new SimpleCommandExceptionType(Text.literal("No settler with that name that you own is loaded."));

    private SettlerCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        LiteralArgumentBuilder<ServerCommandSource> root = literal("settler");

        root.then(literal("summon").then(argument("name", StringArgumentType.word()).executes(SettlerCommands::summon)));
        root.then(literal("list").executes(SettlerCommands::list));
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
        root.then(literal("usage").requires(src -> src.hasPermissionLevel(2)).executes(SettlerCommands::usage));
        root.then(literal("consent")
                .then(literal("on").executes(c -> consent(c, true)))
                .then(literal("off").executes(c -> consent(c, false)))
                .executes(c -> consent(c, null)));

        root.then(literal("board").executes(SettlerCommands::board));
        root.then(literal("task")
                .then(literal("post").then(argument("item", IdentifierArgumentType.identifier())
                        .suggests((c, b) -> CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), b))
                        .then(argument("count", IntegerArgumentType.integer(Board.MIN_COUNT, Board.MAX_COUNT))
                                .executes(c -> postTask(c, ""))
                                .then(argument("note", StringArgumentType.greedyString())
                                        .executes(c -> postTask(c, StringArgumentType.getString(c, "note")))))))
                .then(literal("cancel").then(argument("id", StringArgumentType.word()).executes(SettlerCommands::cancelTask))));

        var node = d.register(root);
        d.register(literal("sm").redirect(node)); // short alias
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
        SettlerEntity settler = SettlerMind.SETTLER.create(world);
        if (settler == null) return 0;
        settler.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), 0);
        settler.initialize(world, world.getLocalDifficulty(player.getBlockPos()), SpawnReason.COMMAND, null, null);
        settler.setOwner(player);
        settler.setSettlerName(name);
        world.spawnEntity(settler);
        c.getSource().sendFeedback(() -> Text.literal(name + " has arrived. Talk to them in chat using their name. "
                + "/settler mind " + name + " shows what they think and remember. Sneak + right-click: inventory."), false);
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
                + (remote ? " (Your words are sent to the configured AI provider only if you opt in: /settler consent on|off)"
                          : " (No AI provider is configured; nothing leaves this server.)");
        c.getSource().sendFeedback(() -> Text.literal(msg), false);
        return 1;
    }

    // ------------------------------------------------------------------ settlement board

    private static int board(CommandContext<ServerCommandSource> c) {
        MindHost host = MindHost.get();
        if (host == null) return 0;
        List<String> active = new ArrayList<>();
        List<String> closed = new ArrayList<>();
        for (BoardTask t : host.board().tasks()) (t.active() ? active : closed).add(describe(t));
        StringBuilder sb = new StringBuilder("Settlement board");
        if (active.isEmpty()) sb.append(": no open tasks. Post one with /settler task post <item> <count> [note]");
        else sb.append(" (").append(active.size()).append(" open):");
        for (String line : active) sb.append("\n ").append(line);
        if (!closed.isEmpty()) {
            sb.append("\nRecently closed:");
            for (String line : closed.subList(Math.max(0, closed.size() - 5), closed.size())) sb.append("\n ").append(line);
        }
        String text = sb.toString();
        c.getSource().sendFeedback(() -> Text.literal(text), false);
        return active.size();
    }

    private static String describe(BoardTask t) {
        String state = switch (t.status) {
            case OPEN -> "open";
            case CLAIMED -> "taken by " + t.claimant;
            case DONE -> "done by " + t.claimant;
            case CANCELLED -> "cancelled";
        };
        return t.id + ": gather " + t.count + " " + t.item + " for " + t.postedBy + " - " + state + ", " + t.progress + "/" + t.count
                + (t.note == null || t.note.isEmpty() ? "" : " - \"" + t.note + "\"");
    }

    private static int postTask(CommandContext<ServerCommandSource> c, String rawNote) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        MindHost host = MindHost.get();
        if (host == null) return 0;
        Identifier item = IdentifierArgumentType.getIdentifier(c, "item");
        int count = IntegerArgumentType.getInteger(c, "count");
        // Notes reach settlers' prompts: plain printable text only, bounded.
        String note = rawNote.replaceAll("[\\p{Cntrl}\u00a7]", "").trim();
        if (note.length() > Board.MAX_NOTE) note = note.substring(0, Board.MAX_NOTE);
        Board.Result r = host.postTask(p.getGameProfile().getName(), item, count, note);
        if (!r.ok()) throw new SimpleCommandExceptionType(Text.literal(boardError(r.code(), item.toString()))).create();
        BoardTask t = r.task();
        c.getSource().sendFeedback(() -> Text.literal("Posted " + t.id + ": gather " + t.count + " " + t.item
                + ". Settlers can see it and may choose to take it. Cancel with /settler task cancel " + t.id), false);
        return 1;
    }

    private static int cancelTask(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        MindHost host = MindHost.get();
        if (host == null) return 0;
        String id = StringArgumentType.getString(c, "id").toUpperCase(Locale.ROOT);
        ServerPlayerEntity p = c.getSource().getPlayer();
        String by = p != null ? p.getGameProfile().getName() : c.getSource().getName();
        Board.Result r = host.cancelTask(id, by, c.getSource().hasPermissionLevel(2));
        if (!r.ok()) throw new SimpleCommandExceptionType(Text.literal(boardError(r.code(), id))).create();
        c.getSource().sendFeedback(() -> Text.literal("Cancelled " + r.task().id + " (gather " + r.task().count + " "
                + r.task().item + ")."), false);
        return 1;
    }

    private static String boardError(String code, String subject) {
        return switch (code) {
            case Board.INVALID_ITEM -> "There is no item called " + subject + ".";
            case Board.INVALID_COUNT -> "The count must be from " + Board.MIN_COUNT + " to " + Board.MAX_COUNT + ".";
            case Board.BOARD_FULL -> "The board is full (" + Board.MAX_ACTIVE_TASKS + " open tasks). Cancel or finish some first.";
            case Board.TOO_MANY_POSTS -> "You already have " + Board.MAX_ACTIVE_POSTS_PER_POSTER + " open tasks on the board.";
            case Board.NOT_FOUND -> "There is no task " + subject + " on the board. See /settler board.";
            case Board.NOT_ALLOWED -> "Only whoever posted " + subject + " (or an operator) can cancel it.";
            case Board.ALREADY_CLOSED -> "Task " + subject + " is already done or cancelled.";
            default -> "The board refused that (" + code + ").";
        };
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
            world.getEntitiesByType(SettlerMind.SETTLER, e -> true).forEach(fn);
        }
    }
}
