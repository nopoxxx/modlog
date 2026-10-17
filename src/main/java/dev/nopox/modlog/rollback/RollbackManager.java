package dev.nopox.modlog.rollback;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
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
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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

    /** What a finished job changed, kept so {@code /ml undo} can apply the opposite. */
    private record Done(List<StoredAction> applied, boolean rollback) {
    }

    private static final class Job {
        final CommandSourceStack source;
        final String sourceKey;
        final boolean rollback;
        final List<StoredAction> actions;
        final List<StoredAction> applied = new ArrayList<>();
        final Map<String, Integer> skipReasons = new TreeMap<>();
        final Runnable onFinish;
        final long started = System.currentTimeMillis();
        long lastProgress = started;
        int index;
        int blocks;
        int items;
        int partial;
        int skipped;

        Job(CommandSourceStack source, boolean rollback, List<StoredAction> actions, Runnable onFinish) {
            this.source = source;
            this.onFinish = onFinish;
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
                begin(new Job(source, rollback, actions, onFinish));
                started = true;
            }
            if (!started) {
                onFinish.run();
            }
        }));
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
        begin(new Job(source, !last.rollback, actions, onFinish));
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
                result = ActionApplier.apply(server, action, job.rollback);
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

    private static void finish(Job job, boolean interrupted) {
        running = null;
        LAST.put(job.sourceKey, new Done(job.applied, job.rollback));

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
        if (!interrupted && !job.applied.isEmpty()) {
            job.source.sendSystemMessage(Component.literal("[Undo]").withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ml undo"))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/ml undo")))));
        }
        job.onFinish.run();
    }
}
