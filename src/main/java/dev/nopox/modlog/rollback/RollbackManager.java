package dev.nopox.modlog.rollback;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.db.LookupQuery;
import dev.nopox.modlog.db.Storage;
import dev.nopox.modlog.db.StoredAction;
import dev.nopox.modlog.lookup.LookupPresenter;
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

        String verb() {
            return rollback ? "Rollback" : "Restore";
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
            source.sendFailure(Component.literal("ModLog database is not available, see server log"));
            return;
        }
        if (running != null || starting) {
            source.sendFailure(Component.literal("Another rollback/restore is in progress, try again when it finishes"));
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
                source.sendFailure(Component.literal("Rollback failed: " + cause.getMessage()));
            } else if (actions.isEmpty()) {
                source.sendSystemMessage(Component.literal("Nothing to " + (rollback ? "roll back" : "restore") + ".")
                        .withStyle(ChatFormatting.GRAY));
            } else if (actions.size() > max) {
                source.sendFailure(Component.literal("More than %d actions match; narrow it down with u:, t: or r:"
                        .formatted(max)));
            } else {
                begin(new Job(source, rollback, actions, onFinish, Map.of()));
                started = true;
            }
            if (!started) {
                onFinish.run();
            }
        }));
    }

    /** Drops can't be undone: leave them out unless the query asks for them explicitly. */
    static LookupQuery undoable(LookupQuery query) {
        if (!query.actions().isEmpty()) {
            return query;
        }
        return new LookupQuery(query.actors(), EnumSet.complementOf(EnumSet.of(ActionType.ITEM_DROP)), query.materials(),
                query.world(), query.center(), query.radius(), query.sinceTime(), query.ids(), query.hideRolledBack());
    }

    /** Reverts the source's last rollback or restore. Main thread. */
    public static void undo(CommandSourceStack source) {
        undo(source, () -> {
        });
    }

    public static void undo(CommandSourceStack source, Runnable onFinish) {
        if (running != null || starting) {
            source.sendFailure(Component.literal("Another rollback/restore is in progress, try again when it finishes"));
            return;
        }
        Done last = LAST.remove(LookupPresenter.sourceKey(source));
        if (last == null) {
            source.sendFailure(Component.literal("Nothing to undo."));
            return;
        }
        // Apply the opposite operation in reverse order
        List<StoredAction> actions = new ArrayList<>(last.applied);
        java.util.Collections.reverse(actions);
        begin(new Job(source, !last.rollback, actions, onFinish, last.taken));
    }

    private static void begin(Job job) {
        running = job;
        job.source.sendSystemMessage(Component.literal("%s of %d actions started..."
                .formatted(job.verb(), job.actions.size())).withStyle(ChatFormatting.DARK_AQUA));
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
                result = ActionApplier.Result.skipped("error, see server log");
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
            job.source.sendSystemMessage(Component.literal("%s: %d%% (%d/%d)".formatted(job.verb(),
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
            if (player != null) {
                // Drops what doesn't fit at the player's feet
                player.getInventory().placeItemBackInInventory(t.stack().copy());
            } else if (level != null) {
                Containers.dropItemStack(level, action.x() + 0.5, action.y() + 0.5, action.z() + 0.5, t.stack().copy());
            }
        }
    }

    private static void report(CommandSourceStack source, Reclaimer reclaimer) {
        if (reclaimer.reclaimed() > 0) {
            source.sendSystemMessage(Component.literal(("Took back %d items given out by rolled-back actions "
                    + "(%d from players, %d from the ground, %d moved on and already undone)").formatted(reclaimer.reclaimed(),
                    reclaimer.fromPlayers, reclaimer.fromGround, reclaimer.fromCredit)).withStyle(ChatFormatting.DARK_AQUA));
        }
        if (reclaimer.overflowDropped > 0) {
            source.sendSystemMessage(Component.literal("%d taken-back items didn't fit into their container and were dropped next to it"
                    .formatted(reclaimer.overflowDropped)).withStyle(ChatFormatting.GOLD));
        }
        if (reclaimer.overflowDiscarded > 0) {
            source.sendSystemMessage(Component.literal(("%d taken-back items belonged to a container this rollback removed; "
                    + "the restored original holds them").formatted(reclaimer.overflowDiscarded)).withStyle(ChatFormatting.GRAY));
        }
        List<String> missing = reclaimer.missingSummary();
        if (!missing.isEmpty()) {
            source.sendSystemMessage(Component.literal("Not taken back, possible duplicates:").withStyle(ChatFormatting.GOLD));
            for (int i = 0; i < missing.size() && i < MAX_MISSING_LINES; i++) {
                source.sendSystemMessage(Component.literal("  " + missing.get(i)).withStyle(ChatFormatting.GOLD));
            }
            if (missing.size() > MAX_MISSING_LINES) {
                source.sendSystemMessage(Component.literal("  ... and %d more".formatted(missing.size() - MAX_MISSING_LINES))
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
        String summary = String.format(Locale.ROOT, "%s %s in %.1fs: %d blocks, %d items changed",
                job.verb(), interrupted ? "INTERRUPTED (server stopping)" : "finished", seconds, job.blocks, job.items);
        if (job.partial > 0) {
            summary += ", " + job.partial + " partially";
        }
        job.source.sendSystemMessage(Component.literal(summary).withStyle(ChatFormatting.DARK_AQUA));
        if (job.skipped > 0) {
            StringBuilder reasons = new StringBuilder();
            job.skipReasons.forEach((reason, n) -> reasons.append(reasons.isEmpty() ? "" : ", ").append(n).append(' ').append(reason));
            job.source.sendSystemMessage(Component.literal("Skipped %d: %s".formatted(job.skipped, reasons))
                    .withStyle(ChatFormatting.GOLD));
        }
        if (job.reclaimer != null) {
            job.reclaimer.settleOverflow();
            report(job.source, job.reclaimer);
        }
        if (!interrupted && !job.applied.isEmpty()) {
            job.source.sendSystemMessage(Component.literal("[Undo]").withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ml undo"))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/ml undo")))));
        }
        job.onFinish.run();
    }
}
