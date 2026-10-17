package dev.nopox.modlog.db;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.action.LoggedAction;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns the database, the writer thread and the lookup thread for one server run. */
public final class Storage {
    private final Database database;
    private final ActionWriter writer;
    private final ExecutorService lookups = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ModLog Lookup");
        t.setDaemon(true);
        return t;
    });

    private Storage(Database database, long flushIntervalMs) {
        this.database = database;
        this.writer = new ActionWriter(database, flushIntervalMs);
    }

    public static Storage open(Path file, long flushIntervalMs) throws SQLException {
        Storage storage = new Storage(new Database(file), flushIntervalMs);
        storage.writer.start();
        return storage;
    }

    public void log(LoggedAction action) {
        writer.submit(action);
    }

    public int pendingWrites() {
        return writer.pending();
    }

    public CompletableFuture<Database.Page> lookup(LookupQuery query, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return database.lookup(query, offset, limit);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, lookups);
    }

    public CompletableFuture<List<StoredAction>> fetchForRollback(LookupQuery query, boolean rolledBack, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return database.fetchForRollback(query, rolledBack, limit);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, lookups);
    }

    public CompletableFuture<Void> markRolledBack(List<Long> ids, boolean rolledBack) {
        return CompletableFuture.runAsync(() -> {
            try {
                database.markRolledBack(ids, rolledBack);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, lookups);
    }

    public void close() {
        lookups.shutdown();
        writer.stop();
        try {
            lookups.awaitTermination(5, TimeUnit.SECONDS);
            database.close();
        } catch (SQLException e) {
            ModLog.LOGGER.error("Failed to close database", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
