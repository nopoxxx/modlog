package dev.nopox.modlog.rollback;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.db.LookupQuery;
import dev.nopox.modlog.db.Storage;
import dev.nopox.modlog.db.StoredAction;
import dev.nopox.modlog.util.Ids;
import dev.nopox.modlog.util.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * {@code #preview} for rollback/restore: shows the player what the operation would change without changing the
 * world. Block changes are sent to that player's client only; container changes are summarised in chat. The player
 * then applies it or cancels, which resends the real blocks. Main thread only.
 */
public final class RollbackPreview {
    /** More fake block updates than this would flood the client. */
    private static final int MAX_BLOCKS = 20_000;
    private static final int MAX_ITEM_LINES = 8;

    private record Preview(LookupQuery query, boolean rollback, ServerLevel level, List<BlockPos> shown) {
    }

    private static final Map<UUID, Preview> PREVIEWS = new HashMap<>();

    private RollbackPreview() {
    }

    public static void register(IEventBus bus) {
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> PREVIEWS.remove(e.getEntity().getUUID()));
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                cancel(player, false);
            }
        });
    }

    public static void start(ServerPlayer player, LookupQuery query, boolean rollback) {
        Storage storage = ModLog.storage();
        if (storage == null) {
            player.sendSystemMessage(Lang.text(player, "error.database_unavailable").withStyle(ChatFormatting.RED));
            return;
        }
        cancel(player, false);
        int max = ModLogConfig.ROLLBACK_MAX_ACTIONS.get();
        storage.fetchForRollback(RollbackManager.undoable(query), !rollback, max + 1).whenComplete((actions, error) -> player.server.execute(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                player.sendSystemMessage(Lang.text(player, "error.preview_failed", cause.getMessage()).withStyle(ChatFormatting.RED));
            } else if (actions.isEmpty()) {
                player.sendSystemMessage(Lang.text(player, rollback ? "rollback.nothing" : "restore.nothing")
                        .withStyle(ChatFormatting.GRAY));
            } else if (actions.size() > max) {
                player.sendSystemMessage(Lang.text(player, "error.too_many", max).withStyle(ChatFormatting.RED));
            } else if (!player.hasDisconnected()) {
                show(player, query, rollback, actions);
            }
        }));
    }

    private static void show(ServerPlayer player, LookupQuery query, boolean rollback, List<StoredAction> actions) {
        ServerLevel level = player.serverLevel();
        String world = level.dimension().location().toString();

        // Simulate in application order, so several actions on one position end in the state the last one leaves
        Map<BlockPos, BlockState> simulated = new LinkedHashMap<>();
        Map<String, Integer> items = new TreeMap<>();
        int elsewhere = 0;
        int skipped = 0;
        for (StoredAction a : actions) {
            switch (a.type()) {
                case BLOCK_BREAK, BLOCK_PLACE -> {
                    if (!a.world().equals(world)) {
                        elsewhere++;
                        continue;
                    }
                    BlockPos pos = new BlockPos(a.x(), a.y(), a.z());
                    BlockState current = simulated.containsKey(pos) ? simulated.get(pos) : level.getBlockState(pos);
                    BlockState result = ActionApplier.simulate(a, rollback, current);
                    if (result == null) {
                        skipped++;
                    } else {
                        simulated.put(pos, result);
                    }
                }
                case CONTAINER_ADD, CONTAINER_REMOVE, ITEM_PICKUP -> {
                    // Rolling back an add/pickup takes items out; rolling back a remove puts them back
                    boolean out = (a.type() != ActionType.CONTAINER_REMOVE) == rollback;
                    items.merge((out ? "- " : "+ ") + Ids.shortId(a.material()), a.amount(), Integer::sum);
                }
                case ITEM_DROP, ENTITY_KILL, SIGN_EDIT, CHAT, COMMAND -> {
                }
            }
        }

        List<BlockPos> shown = simulated.entrySet().stream()
                .filter(e -> e.getValue() != level.getBlockState(e.getKey()))
                .limit(MAX_BLOCKS)
                .map(Map.Entry::getKey)
                .toList();
        for (BlockPos pos : shown) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, simulated.get(pos)));
        }
        PREVIEWS.put(player.getUUID(), new Preview(query, rollback, level, shown));

        String lang = Lang.of(player);
        String verb = Lang.tr(lang, rollback ? "rollback.name" : "restore.name");
        player.sendSystemMessage(Lang.text(lang, "preview.header", verb).withStyle(ChatFormatting.DARK_AQUA));
        player.sendSystemMessage(Lang.text(lang, "preview.summary", actions.size(), shown.size()));
        if (skipped > 0) {
            player.sendSystemMessage(Lang.text(lang, "preview.skipped", skipped)
                    .withStyle(ChatFormatting.GOLD));
        }
        if (elsewhere > 0) {
            player.sendSystemMessage(Lang.text(lang, "preview.elsewhere", elsewhere)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (simulated.size() > MAX_BLOCKS) {
            player.sendSystemMessage(Lang.text(lang, "preview.capped", MAX_BLOCKS)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (!items.isEmpty()) {
            player.sendSystemMessage(Lang.text(lang, "preview.items"));
            int line = 0;
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                if (line++ == MAX_ITEM_LINES) {
                    player.sendSystemMessage(Component.literal("  " + Lang.tr(lang, "list.more", items.size() - MAX_ITEM_LINES))
                            .withStyle(ChatFormatting.GRAY));
                    break;
                }
                player.sendSystemMessage(Component.literal("  %s x %d".formatted(e.getKey(), e.getValue()))
                        .withStyle(e.getKey().startsWith("+") ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
        }
        MutableComponent buttons = Component.empty()
                .append(button("[" + Lang.tr(lang, "button.apply") + "]", "/ml preview confirm", ChatFormatting.GREEN,
                        Lang.tr(lang, "preview.apply_hint", verb)))
                .append(" ")
                .append(button("[" + Lang.tr(lang, "button.cancel") + "]", "/ml preview cancel", ChatFormatting.RED,
                        Lang.tr(lang, "preview.cancel_hint")));
        player.sendSystemMessage(buttons);
    }

    /** Runs the previewed operation. */
    public static void confirm(ServerPlayer player) {
        Preview preview = PREVIEWS.remove(player.getUUID());
        if (preview == null) {
            player.sendSystemMessage(Lang.text(player, "preview.none_to_apply")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        // Positions the operation skips would otherwise keep showing the preview
        RollbackManager.start(player.createCommandSourceStack(), preview.query, preview.rollback,
                () -> resend(player, preview));
    }

    /** Drops the preview and shows the real blocks again. */
    public static void cancel(ServerPlayer player, boolean tell) {
        Preview preview = PREVIEWS.remove(player.getUUID());
        if (preview != null) {
            resend(player, preview);
            if (tell) {
                player.sendSystemMessage(Lang.text(player, "preview.cancelled").withStyle(ChatFormatting.GRAY));
            }
        } else if (tell) {
            player.sendSystemMessage(Lang.text(player, "preview.none_to_cancel").withStyle(ChatFormatting.GRAY));
        }
    }

    private static void resend(ServerPlayer player, Preview preview) {
        if (player.hasDisconnected() || player.serverLevel() != preview.level) {
            return;
        }
        for (BlockPos pos : preview.shown) {
            player.connection.send(new ClientboundBlockUpdatePacket(preview.level, pos));
        }
    }

    private static Component button(String text, String command, ChatFormatting color, @Nullable String hover) {
        Style style = Style.EMPTY.withColor(color).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        if (hover != null) {
            style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover)));
        }
        return Component.literal(text).withStyle(style);
    }
}
