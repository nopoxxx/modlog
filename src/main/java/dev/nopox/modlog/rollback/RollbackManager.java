package dev.nopox.modlog.rollback;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.db.LookupQuery;
import dev.nopox.modlog.db.Storage;
import dev.nopox.modlog.db.StoredAction;
import dev.nopox.modlog.lookup.LookupPresenter;
import dev.nopox.modlog.util.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs rollbacks and restores a slice per tick on the main thread. One job at a time, so two operations
 * on the same area can't interleave.
 */
public final class RollbackManager {
    private static final long PROGRESS_INTERVAL_MS = 5000;

    /**
     * What a finished job changed, kept so {@code /ml undo} can apply the opposite.
     *
     * @param taken items a rollback took back from players or the ground, by action id; undo returns them
     */
    private record Done(List<StoredAction> applied, boolean rollback, Map<Long, List<Reclaimer.Taken>> taken) {
    }

    private static final int MAX_MISSING_LINES = 8;

    private static final class Job {
        final CommandSourceStack source;
        final String sourceKey;
        final boolean rollback;
        final List<StoredAction> actions;
        final List<StoredAction> applied = new ArrayList<>();
        final Map<String, Integer> skipReasons = new TreeMap<>();
        final Runnable onFinish;
        /** Takes back what rolled-back actions gave players; rollbacks only */
        final @Nullable Reclaimer reclaimer;
        /** For an undo of a rollback: items to return as their actions are restored */
        final Map<Long, List<Reclaimer.Taken>> giveBack;
        final long started = System.currentTimeMillis();
        long lastProgress = started;
        int index;
        int blocks;
        int items;
        int partial;
        int skipped;

        Job(CommandSourceStack source, boolean rollback, List<StoredAction> actions, Runnable onFinish,
            Map<Long, List<Reclaimer.Taken>> giveBack) {
            this.source = source;
            this.onFinish = onFinish;
            this.giveBack = giveBack;
            this.reclaimer = rollback && ModLogConfig.ROLLBACK_RECLAIM.get() ? new Reclaimer(source.getServer()) : null;
            this.sourceKey = LookupPresenter.sourceKey(source);
            this.rollback = rollback;
            this.actions = actions;
        }

        /** "Rollback" or "Restore" in the source's language. */
        String verb() {
            return Lang.tr(source, rollback ? "rollback.name" : "restore.name");
        }
    }

    private static @Nullable Job running;
    /** A job whose actions are being fetched; blocks new jobs until it starts. */
    private static boolean starting;
    private static final Map<String, Done> LAST = new ConcurrentHashMap<>();

    private RollbackManager() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(RollbackManager::onTick);
        bus.addListener((ServerStoppingEvent e) -> {
            if (running != null) {
                finish(running, true);
            }
            starting = false;
            LAST.clear();
        });
    }

    /** Fetches matching actions and starts applying them. Main thread. */
    public static void start(CommandSourceStack source, LookupQuery query, boolean rollback) {
        start(source, query, rollback, () -> {
        });
    }

    /** @param onFinish runs on the main thread when the operation ends (not if it was refused right away) */
    public static void start(CommandSourceStack source, LookupQuery query, boolean rollback, Runnable onFinish) {
        Storage storage = ModLog.storage();
        if (storage == null) {
            source.sendFailure(Lang.text(source, "error.database_unavailable"));
            return;
        }
        if (running != null || starting) {
            source.sendFailure(Lang.text(source, "error.busy"));
            return;
        }
        query = undoable(query);
        starting = true;
        int max = ModLogConfig.ROLLBACK_MAX_ACTIONS.get();
        MinecraftServer server = source.getServer();
        storage.fetchForRollback(query, !rollback, max + 1).whenComplete((actions, error) -> server.execute(() -> {
            starting = false;
            boolean started = false;
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                ModLog.LOGGER.error("Rollback query failed", cause);
                source.sendFailure(Lang.text(source, "error.rollback_failed", cause.getMessage()));
            } else if (actions.isEmpty()) {
                source.sendSystemMessage(Lang.text(source, rollback ? "rollback.nothing" : "restore.nothing")
                        .withStyle(ChatFormatting.GRAY));
            } else if (actions.size() > max) {
                source.sendFailure(Lang.text(source, "error.too_many", max));
            } else {
                begin(new Job(source, rollback, actions, onFinish, Map.of()));
                started = true;
            }
            if (!started) {
                onFinish.run();
            }
        }));
    }

    /** Drops, chat and commands can't be undone: leave them out unless the query asks for them explicitly. */
    static LookupQuery undoable(LookupQuery query) {
        if (!query.actions().isEmpty()) {
            return query;
        }
        EnumSet<ActionType> undoable = EnumSet.noneOf(ActionType.class);
        for (ActionType type : ActionType.values()) {
            if (!type.lookupOnly()) {
                undoable.add(type);
            }
        }
        return new LookupQuery(query.actors(), undoable, query.materials(),
                query.world(), query.center(), query.radius(), query.sinceTime(), query.ids(), query.hideRolledBack());
    }

    /** Reverts the source's last rollback or restore. Main thread. */
    public static void undo(CommandSourceStack source) {
        undo(source, () -> {
        });
    }

    public static void undo(CommandSourceStack source, Runnable onFinish) {
        if (running != null || starting) {
            source.sendFailure(Lang.text(source, "error.busy"));
            return;
        }
        Done last = LAST.remove(LookupPresenter.sourceKey(source));
        if (last == null) {
            source.sendFailure(Lang.text(source, "undo.nothing"));
            return;
        }
        // Apply the opposite operation in reverse order
        List<StoredAction> actions = new ArrayList<>(last.applied);
        java.util.Collections.reverse(actions);
        begin(new Job(source, !last.rollback, actions, onFinish, last.taken));
    }

    private static void begin(Job job) {
        running = job;
        job.source.sendSystemMessage(Lang.text(job.source, "rollback.started", job.verb(), job.actions.size())
                .withStyle(ChatFormatting.DARK_AQUA));
    }

    private static void onTick(ServerTickEvent.Post event) {
        Job job = running;
        if (job == null) {
            return;
        }
        MinecraftServer server = event.getServer();
        int end = Math.min(job.actions.size(), job.index + ModLogConfig.ROLLBACK_PER_TICK.get());
        for (; job.index < end; job.index++) {
            StoredAction action = job.actions.get(job.index);
            ActionApplier.Result result;
            try {
                if (job.reclaimer != null) {
                    job.reclaimer.beginAction(action.id());
                }
                result = ActionApplier.apply(server, action, job.rollback, job.reclaimer);
                if (result.outcome() != ActionApplier.Outcome.SKIPPED) {
                    giveBack(server, action, job.giveBack.get(action.id()));
                }
            } catch (RuntimeException e) {
                ModLog.LOGGER.error("Failed to apply action #{}", action.id(), e);
                result = ActionApplier.Result.skipped("skip.error");
            }
            job.blocks += result.blocks();
            job.items += result.items();
            switch (result.outcome()) {
                case DONE -> job.applied.add(action);
                case PARTIAL -> {
                    job.applied.add(action);
                    job.partial++;
                }
                case SKIPPED -> {
                    job.skipped++;
                    job.skipReasons.merge(result.skipReason(), 1, Integer::sum);
                }
            }
        }

        if (job.index >= job.actions.size()) {
            finish(job, false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - job.lastProgress >= PROGRESS_INTERVAL_MS) {
            job.lastProgress = now;
            job.source.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "%s: %d%% (%d/%d)", job.verb(),
                    job.index * 100 / job.actions.size(), job.index, job.actions.size())).withStyle(ChatFormatting.GRAY));
        }
    }

    /** Undo of a rollback: return what it took back for this action to the players / the ground it came from. */
    private static void giveBack(MinecraftServer server, StoredAction action, @Nullable List<Reclaimer.Taken> taken) {
        if (taken == null) {
            return;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(action.world())));
        for (Reclaimer.Taken t : taken) {
            ServerPlayer player = t.player() == null ? null : server.getPlayerList().getPlayer(t.player());
            ItemStack left = t.stack().copy();
            if (player != null) {
                // Drops what doesn't fit at the player's feet
                player.getInventory().placeItemBackInInventory(left);
                continue;
            }
            if (t.player() != null) {
                left = OfflineInventories.give(server, t.player(), left);
            }
            if (!left.isEmpty() && level != null) {
                Containers.dropItemStack(level, action.x() + 0.5, action.y() + 0.5, action.z() + 0.5, left);
            }
        }
    }

    private static void report(CommandSourceStack source, Reclaimer reclaimer) {
        String lang = Lang.of(source);
        if (reclaimer.reclaimed() > 0) {
            String offline = reclaimer.fromOffline > 0 ? Lang.tr(lang, "reclaim.offline_part", reclaimer.fromOffline) : "";
            source.sendSystemMessage(Lang.text(lang, "reclaim.summary", reclaimer.reclaimed(), reclaimer.fromPlayers, offline,
                    reclaimer.fromGround, reclaimer.fromCredit).withStyle(ChatFormatting.DARK_AQUA));
        }
        if (reclaimer.overflowDropped > 0) {
            source.sendSystemMessage(Lang.text(lang, "reclaim.overflow_dropped", reclaimer.overflowDropped)
                    .withStyle(ChatFormatting.GOLD));
        }
        if (reclaimer.overflowDiscarded > 0) {
            source.sendSystemMessage(Lang.text(lang, "reclaim.overflow_discarded", reclaimer.overflowDiscarded)
                    .withStyle(ChatFormatting.GRAY));
        }
        List<String> missing = reclaimer.missingSummary(lang);
        if (!missing.isEmpty()) {
            source.sendSystemMessage(Lang.text(lang, "reclaim.missing").withStyle(ChatFormatting.GOLD));
            for (int i = 0; i < missing.size() && i < MAX_MISSING_LINES; i++) {
                source.sendSystemMessage(Component.literal("  " + missing.get(i)).withStyle(ChatFormatting.GOLD));
            }
            if (missing.size() > MAX_MISSING_LINES) {
                source.sendSystemMessage(Component.literal("  " + Lang.tr(lang, "list.more", missing.size() - MAX_MISSING_LINES))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    private static void finish(Job job, boolean interrupted) {
        running = null;
        LAST.put(job.sourceKey, new Done(job.applied, job.rollback,
                job.reclaimer == null ? Map.of() : job.reclaimer.takenByAction()));

        Storage storage = ModLog.storage();
        if (storage != null && !job.applied.isEmpty()) {
            storage.markRolledBack(job.applied.stream().map(StoredAction::id).toList(), job.rollback)
                    .exceptionally(e -> {
                        ModLog.LOGGER.error("Failed to update rolled_back flags", e);
                        return null;
                    });
        }

        double seconds = (System.currentTimeMillis() - job.started) / 1000.0;
        String lang = Lang.of(job.source);
        String summary = Lang.tr(lang, interrupted ? "rollback.interrupted" : "rollback.finished", job.verb(), seconds,
                job.blocks, job.items);
        if (job.partial > 0) {
            summary += Lang.tr(lang, "rollback.partially", job.partial);
        }
        job.source.sendSystemMessage(Component.literal(summary).withStyle(ChatFormatting.DARK_AQUA));
        if (job.skipped > 0) {
            StringBuilder reasons = new StringBuilder();
            job.skipReasons.forEach((reason, n) -> reasons.append(reasons.isEmpty() ? "" : ", ").append(n).append(' ')
                    .append(Lang.tr(lang, reason)));
            job.source.sendSystemMessage(Lang.text(lang, "rollback.skipped", job.skipped, reasons).withStyle(ChatFormatting.GOLD));
        }
        if (job.reclaimer != null) {
            job.reclaimer.settleOverflow();
            report(job.source, job.reclaimer);
        }
        if (!interrupted && !job.applied.isEmpty()) {
            job.source.sendSystemMessage(Component.literal("[" + Lang.tr(lang, "button.undo") + "]").withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ml undo"))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/ml undo")))));
        }
        job.onFinish.run();
    }
}
