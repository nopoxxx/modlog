package dev.nopox.modlog.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.db.LookupQuery;
import dev.nopox.modlog.db.Storage;
import dev.nopox.modlog.gui.LogBrowser;
import dev.nopox.modlog.inspect.Inspector;
import dev.nopox.modlog.lookup.LookupPresenter;
import dev.nopox.modlog.rollback.RollbackManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** {@code /ml} (alias {@code /modlog}). */
public final class ModLogCommand {
    private ModLogCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(literal("ml")
                .requires(s -> s.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ModLogCommand::help)
                .then(literal("help").executes(ModLogCommand::help))
                .then(literal("inspect").executes(ModLogCommand::inspect))
                .then(literal("i").executes(ModLogCommand::inspect))
                .then(literal("lookup")
                        .executes(ctx -> lookup(ctx, ""))
                        .then(argument("params", StringArgumentType.greedyString())
                                .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "params")))))
                .then(literal("l")
                        .executes(ctx -> lookup(ctx, ""))
                        .then(argument("params", StringArgumentType.greedyString())
                                .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "params")))))
                .then(literal("gui")
                        .executes(ctx -> gui(ctx, ""))
                        .then(argument("params", StringArgumentType.greedyString())
                                .executes(ctx -> gui(ctx, StringArgumentType.getString(ctx, "params")))))
                .then(literal("page")
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ModLogCommand::page)))
                .then(literal("status").executes(ModLogCommand::status))
                .then(rollbackNode("rollback", true))
                .then(rollbackNode("rb", true))
                .then(rollbackNode("restore", false))
                .then(rollbackNode("rs", false))
                .then(literal("undo")
                        .requires(s -> s.hasPermission(Commands.LEVEL_ADMINS))
                        .executes(ctx -> {
                            RollbackManager.undo(ctx.getSource());
                            return 1;
                        })));

        dispatcher.register(literal("modlog")
                .requires(s -> s.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ModLogCommand::help)
                .redirect(root));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rollbackNode(String name, boolean rollback) {
        return literal(name)
                .requires(s -> s.hasPermission(Commands.LEVEL_ADMINS))
                .then(argument("params", StringArgumentType.greedyString())
                        .executes(ctx -> rollback(ctx, StringArgumentType.getString(ctx, "params"), rollback)));
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        s.sendSystemMessage(Component.literal("----- ModLog -----").withStyle(ChatFormatting.DARK_AQUA));
        s.sendSystemMessage(Component.literal("/ml gui [u:<users>] [b:<blocks>] - browse logs in a GUI"));
        s.sendSystemMessage(Component.literal("/ml i - toggle inspector"));
        s.sendSystemMessage(Component.literal("/ml l " + LookupParams.USAGE + " - search"));
        s.sendSystemMessage(Component.literal("/ml page <n> - page of the last search"));
        s.sendSystemMessage(Component.literal("/ml rollback|restore <params> - undo/redo matching actions (t: required)"));
        s.sendSystemMessage(Component.literal("/ml undo - revert your last rollback/restore"));
        s.sendSystemMessage(Component.literal("/ml status - database status"));
        return 1;
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean on = Inspector.toggle(player);
        ctx.getSource().sendSystemMessage(Component.literal("ModLog inspector " + (on ? "enabled" : "disabled"))
                .withStyle(ChatFormatting.DARK_AQUA));
        return 1;
    }

    private static int lookup(CommandContext<CommandSourceStack> ctx, String params) {
        LookupQuery query;
        try {
            query = LookupParams.parse(params, ctx.getSource());
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        LookupPresenter.show(ctx.getSource(), query, false, 1);
        return 1;
    }

    private static int rollback(CommandContext<CommandSourceStack> ctx, String params, boolean rollback) {
        LookupQuery query;
        try {
            query = LookupParams.parse(params, ctx.getSource());
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        // Guard rails against rolling back the whole server by accident
        if (query.sinceTime() == null) {
            ctx.getSource().sendFailure(Component.literal("A time limit is required, e.g. t:1h"));
            return 0;
        }
        boolean radiusGiven = params.toLowerCase(java.util.Locale.ROOT).matches("(^|.*\\s)(r|radius):.*");
        if (query.actors().isEmpty() && !radiusGiven) {
            ctx.getSource().sendFailure(Component.literal("Specify users (u:) or an area (r:10, r:#world, or r:#global to really mean everywhere)"));
            return 0;
        }
        RollbackManager.start(ctx.getSource(), query, rollback);
        return 1;
    }

    private static int gui(CommandContext<CommandSourceStack> ctx, String params) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        LookupQuery prefill;
        try {
            prefill = LookupParams.parse(params, ctx.getSource());
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        // Time, area and action type are picked with the GUI's own buttons
        LogBrowser.open(player, prefill.actors(), prefill.materials());
        return 1;
    }

    private static int page(CommandContext<CommandSourceStack> ctx) {
        int page = IntegerArgumentType.getInteger(ctx, "page");
        if (!LookupPresenter.showPage(ctx.getSource(), page)) {
            ctx.getSource().sendFailure(Component.literal("No previous lookup. Use /ml l or /ml i first."));
            return 0;
        }
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Storage storage = ModLog.storage();
        if (storage == null) {
            ctx.getSource().sendFailure(Component.literal("Database is not available, see server log"));
            return 0;
        }
        ctx.getSource().sendSystemMessage(Component.literal("ModLog: database OK, %d actions waiting to be written"
                .formatted(storage.pendingWrites())));
        return 1;
    }
}
