package dev.nopox.modlog.db;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.action.LoggedAction;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Background thread that drains queued actions into the database in batches,
 * so the server thread never waits on disk I/O.
 */
public final class ActionWriter {
    private static final int MAX_BATCH = 5000;

    private final Database database;
    private final long flushIntervalMs;
    private final BlockingQueue<LoggedAction> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean running = true;

    public ActionWriter(Database database, long flushIntervalMs) {
        this.database = database;
        this.flushIntervalMs = flushIntervalMs;
        this.thread = new Thread(this::run, "ModLog Writer");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public void submit(LoggedAction action) {
        queue.add(action);
    }

    public int pending() {
        return queue.size();
    }

    private void run() {
        List<LoggedAction> batch = new ArrayList<>();
        while (running) {
            try {
                LoggedAction first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                // Let more actions accumulate so one transaction covers them
                Thread.sleep(flushIntervalMs);
                queue.drainTo(batch, MAX_BATCH - batch.size());
                write(batch);
            } catch (InterruptedException e) {
                // stop() interrupts us; the loop condition decides whether to exit
            }
        }
        // Final flush on shutdown, including anything taken before an interrupt
        do {
            queue.drainTo(batch, MAX_BATCH - batch.size());
            write(batch);
        } while (!queue.isEmpty());
    }

    private void write(List<LoggedAction> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            database.insert(batch);
        } catch (SQLException e) {
            ModLog.LOGGER.error("Failed to write {} actions, they are lost", batch.size(), e);
        } finally {
            batch.clear();
        }
    }

    /** Stops the thread after writing everything still queued. */
    public void stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            ModLog.LOGGER.error("Writer did not finish within 30s, {} actions may be lost", queue.size());
        }
    }
}
