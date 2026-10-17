package dev.nopox.modlog.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.Permissions;
import dev.nopox.modlog.db.LookupQuery;
import dev.nopox.modlog.db.Storage;
import dev.nopox.modlog.gui.LogBrowser;
import dev.nopox.modlog.inspect.Inspector;
import dev.nopox.modlog.lookup.LookupPresenter;
import dev.nopox.modlog.rollback.RollbackManager;
import dev.nopox.modlog.rollback.RollbackPreview;
import dev.nopox.modlog.util.Lang;
import dev.nopox.modlog.util.ModLogException;
import dev.nopox.modlog.util.TimeFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** {@code /ml} (alias {@code /modlog}). */
public final class ModLogCommand {
    private static final long MIN_PURGE_AGE_MS = 3_600_000L;

    private ModLogCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(literal("ml")
                .requires(ModLogCommand::canUseAny)
                .executes(ModLogCommand::help)
                .then(literal("help").executes(ModLogCommand::help))
                .then(literal("inspect").requires(s -> Permissions.check(s, Permissions.LOOKUP)).executes(ModLogCommand::inspect))
                .then(literal("i").requires(s -> Permissions.check(s, Permissions.LOOKUP)).executes(ModLogCommand::inspect))
                .then(literal("lookup")
                        .requires(s -> Permissions.check(s, Permissions.LOOKUP))
                        .executes(ctx -> lookup(ctx, ""))
                        .then(argument("params", StringArgumentType.greedyString())
                                .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "params")))))
                .then(literal("l")
                        .requires(s -> Permissions.check(s, Permissions.LOOKUP))
                        .executes(ctx -> lookup(ctx, ""))
                        .then(argument("params", StringArgumentType.greedyString())
                                .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "params")))))
                .then(literal("gui")
                        .requires(s -> Permissions.check(s, Permissions.LOOKUP))
                        .executes(ctx -> gui(ctx, ""))
                        .then(argument("params", StringArgumentType.greedyString())
                                .executes(ctx -> gui(ctx, StringArgumentType.getString(ctx, "params")))))
                .then(literal("page")
                        .requires(s -> Permissions.check(s, Permissions.LOOKUP))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ModLogCommand::page)))
                .then(literal("status").requires(s -> Permissions.check(s, Permissions.LOOKUP)).executes(ModLogCommand::status))
                .then(rollbackNode("rollback", true))
                .then(rollbackNode("rb", true))
                .then(rollbackNode("restore", false))
                .then(rollbackNode("rs", false))
                .then(literal("purge")
                        .requires(s -> Permissions.check(s, Permissions.PURGE))
                        .then(argument("params", StringArgumentType.greedyString())
                                .executes(ctx -> purge(ctx, StringArgumentType.getString(ctx, "params")))))
                .then(literal("preview")
                        .requires(s -> Permissions.check(s, Permissions.ROLLBACK))
                        .then(literal("confirm").executes(ctx -> {
                            RollbackPreview.confirm(ctx.getSource().getPlayerOrException());
                            return 1;
                        }))
                        .then(literal("cancel").executes(ctx -> {
                            RollbackPreview.cancel(ctx.getSource().getPlayerOrException(), true);
                            return 1;
                        })))
                .then(literal("undo")
                        .requires(s -> Permissions.check(s, Permissions.ROLLBACK))
                        .executes(ctx -> {
                            RollbackManager.undo(ctx.getSource());
                            return 1;
                        })));

        dispatcher.register(literal("modlog")
                .requires(ModLogCommand::canUseAny)
                .executes(ModLogCommand::help)
                .redirect(root));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rollbackNode(String name, boolean rollback) {
        return literal(name)
                .requires(s -> Permissions.check(s, Permissions.ROLLBACK))
                .then(argument("params", StringArgumentType.greedyString())
                        .executes(ctx -> rollback(ctx, StringArgumentType.getString(ctx, "params"), rollback)));
    }

    /** Sends a parameter error, translated if it has a key. */
    private static void fail(CommandSourceStack source, IllegalArgumentException e) {
        source.sendFailure(e instanceof ModLogException m ? Lang.text(source, m.key(), m.args()) : Component.literal(e.getMessage()));
    }

    /** {@code /ml} itself is available to anyone with at least one ModLog permission. */
    private static boolean canUseAny(CommandSourceStack s) {
        return Permissions.check(s, Permissions.LOOKUP) || Permissions.check(s, Permissions.ROLLBACK)
                || Permissions.check(s, Permissions.PURGE);
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        s.sendSystemMessage(Component.literal("----- ModLog -----").withStyle(ChatFormatting.DARK_AQUA));
        s.sendSystemMessage(Lang.text(s, "help.gui"));
        s.sendSystemMessage(Lang.text(s, "help.inspect"));
        s.sendSystemMessage(Lang.text(s, "help.lookup", LookupParams.USAGE));
        s.sendSystemMessage(Lang.text(s, "help.page"));
        s.sendSystemMessage(Lang.text(s, "help.rollback"));
        s.sendSystemMessage(Lang.text(s, "help.undo"));
        s.sendSystemMessage(Lang.text(s, "help.purge"));
        s.sendSystemMessage(Lang.text(s, "help.status"));
        return 1;
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean on = Inspector.toggle(player);
        ctx.getSource().sendSystemMessage(Lang.text(ctx.getSource(), on ? "inspector.enabled" : "inspector.disabled")
                .withStyle(ChatFormatting.DARK_AQUA));
        return 1;
    }

    private static int lookup(CommandContext<CommandSourceStack> ctx, String params) {
        LookupQuery query;
        try {
            query = LookupParams.parse(params, ctx.getSource());
        } catch (IllegalArgumentException e) {
            fail(ctx.getSource(), e);
            return 0;
        }
        LookupPresenter.show(ctx.getSource(), query, false, 1);
        return 1;
    }

    private static int rollback(CommandContext<CommandSourceStack> ctx, String params, boolean rollback) {
        // #preview: show the result to the player first instead of applying it
        boolean preview = (" " + params + " ").toLowerCase(java.util.Locale.ROOT).contains(" #preview ");
        params = params.replaceAll("(?i)(^|\\s)#preview(?=\\s|$)", " ").trim();
        if (preview && !(ctx.getSource().getEntity() instanceof ServerPlayer)) {
            ctx.getSource().sendFailure(Lang.text(ctx.getSource(), "error.preview_needs_player"));
            return 0;
        }
        LookupQuery query;
        try {
            query = LookupParams.parse(params, ctx.getSource());
        } catch (IllegalArgumentException e) {
            fail(ctx.getSource(), e);
            return 0;
        }
        // Guard rails against rolling back the whole server by accident
        if (query.sinceTime() == null) {
            ctx.getSource().sendFailure(Lang.text(ctx.getSource(), "error.time_required"));
            return 0;
        }
        boolean radiusGiven = params.toLowerCase(java.util.Locale.ROOT).matches("(^|.*\\s)(r|radius):.*");
        if (query.actors().isEmpty() && !radiusGiven) {
            ctx.getSource().sendFailure(Lang.text(ctx.getSource(), "error.scope_required"));
            return 0;
        }
        if (preview) {
            RollbackPreview.start((ServerPlayer) ctx.getSource().getEntity(), query, rollback);
        } else {
            RollbackManager.start(ctx.getSource(), query, rollback);
        }
        return 1;
    }

    private static int gui(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        LookupQuery prefill;
        try {
            prefill = LookupParams.parse(params, ctx.getSource());
        } catch (IllegalArgumentException e) {
            fail(ctx.getSource(), e);
            return 0;
        }
        // Time, area and action type are picked with the GUI's own buttons
        LogBrowser.open(player, prefill.actors(), prefill.materials());
        return 1;
    }

    /** {@code /ml purge t:30d [vacuum] [confirm]}: without confirm only says how much would be deleted. */
    private static int purge(CommandContext<CommandSourceStack> ctx, String params) {
        CommandSourceStack source = ctx.getSource();
        Long age = null;
        boolean confirm = false;
        boolean vacuum = false;
        for (String token : params.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
            try {
                switch (token) {
                    case "confirm" -> confirm = true;
                    case "vacuum" -> vacuum = true;
                    default -> {
                        if (!token.startsWith("t:")) {
                            throw new ModLogException("error.unknown_parameter", token, "/ml purge t:<age> [vacuum] [confirm]");
                        }
                        age = TimeFormat.parseDuration(token.substring(2));
                    }
                }
            } catch (IllegalArgumentException e) {
                fail(source, e);
                return 0;
            }
        }
        if (age == null || age < MIN_PURGE_AGE_MS) {
            source.sendFailure(Lang.text(source, "error.purge_age"));
            return 0;
        }
        Storage storage = ModLog.storage();
        if (storage == null) {
            source.sendFailure(Lang.text(source, "error.database_unavailable"));
            return 0;
        }
        long cutoff = System.currentTimeMillis() - age;
        String ageText = TimeFormat.duration(age);
        MinecraftServer server = source.getServer();
        if (!confirm) {
            String command = "/ml purge t:%s%s confirm".formatted(ageText, vacuum ? " vacuum" : "");
            storage.countOlderThan(cutoff).whenComplete((count, error) -> server.execute(() -> {
                if (error != null) {
                    source.sendFailure(Lang.text(source, "error.purge_count_failed", error.getMessage()));
                } else if (count == 0) {
                    source.sendSystemMessage(Lang.text(source, "purge.nothing", ageText));
                } else {
                    source.sendSystemMessage(Lang.text(source, "purge.would_delete", count, ageText).withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(command).withStyle(Style.EMPTY.withColor(ChatFormatting.RED)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)))));
                }
            }));
            return 1;
        }
        source.sendSystemMessage(Lang.text(source, vacuum ? "purge.started_vacuum" : "purge.started", ageText)
                .withStyle(ChatFormatting.DARK_AQUA));
        long started = System.currentTimeMillis();
        storage.purge(cutoff, vacuum).whenComplete((deleted, error) -> server.execute(() -> {
            if (error != null) {
                ModLog.LOGGER.error("Purge failed", error);
                source.sendFailure(Lang.text(source, "error.purge_failed", error.getMessage()));
            } else {
                source.sendSystemMessage(Lang.text(source, "purge.done", deleted,
                        (System.currentTimeMillis() - started) / 1000.0).withStyle(ChatFormatting.DARK_AQUA));
                ModLog.LOGGER.info("{} purged {} actions older than {}", source.getTextName(), deleted, ageText);
            }
        }));
        return 1;
    }

    private static int page(CommandContext<CommandSourceStack> ctx) {
        int page = IntegerArgumentType.getInteger(ctx, "page");
        if (!LookupPresenter.showPage(ctx.getSource(), page)) {
            ctx.getSource().sendFailure(Lang.text(ctx.getSource(), "error.no_previous_lookup"));
            return 0;
        }
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Storage storage = ModLog.storage();
        if (storage == null) {
            ctx.getSource().sendFailure(Lang.text(ctx.getSource(), "error.database_unavailable"));
            return 0;
        }
        ctx.getSource().sendSystemMessage(Lang.text(ctx.getSource(), "status.ok", storage.pendingWrites()));
        return 1;
    }
}
