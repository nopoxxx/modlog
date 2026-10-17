package dev.nopox.modlog.db;

import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.action.Actor;
import dev.nopox.modlog.action.LoggedAction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * SQLite storage. Uses two connections so lookups never wait behind a write batch (WAL mode):
 * the write connection belongs to the {@link ActionWriter} thread, the read connection to the lookup executor.
 */
public final class Database implements AutoCloseable {
    private static final String[] SCHEMA = {
            """
            CREATE TABLE IF NOT EXISTS actors (
                id   INTEGER PRIMARY KEY,
                key  TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL COLLATE NOCASE
            )""",
            "CREATE INDEX IF NOT EXISTS actors_name ON actors (name)",
            "CREATE TABLE IF NOT EXISTS worlds (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS materials (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
            """
            CREATE TABLE IF NOT EXISTS actions (
                id          INTEGER PRIMARY KEY,
                time        INTEGER NOT NULL,
                actor       INTEGER NOT NULL REFERENCES actors (id),
                world       INTEGER NOT NULL REFERENCES worlds (id),
                x           INTEGER NOT NULL,
                y           INTEGER NOT NULL,
                z           INTEGER NOT NULL,
                action      INTEGER NOT NULL,
                material    INTEGER NOT NULL REFERENCES materials (id),
                data        TEXT,
                amount      INTEGER NOT NULL DEFAULT 1,
                nbt         BLOB,
                rolled_back INTEGER NOT NULL DEFAULT 0
            )""",
            // Added in 0.2.0
            "ALTER TABLE actions ADD COLUMN contents TEXT",
            "ALTER TABLE actions ADD COLUMN contents_count INTEGER NOT NULL DEFAULT 0",
            "CREATE INDEX IF NOT EXISTS actions_pos ON actions (world, x, z, y)",
            "CREATE INDEX IF NOT EXISTS actions_actor ON actions (actor, time)",
            "CREATE INDEX IF NOT EXISTS actions_time ON actions (time)",
    };

    private final Connection writeConnection;
    private final Connection readConnection;

    // Dictionary caches, only touched from the writer thread
    private final Map<String, Integer> actorIds = new HashMap<>();
    private final Map<String, String> actorNames = new HashMap<>();
    private final Map<String, Integer> worldIds = new HashMap<>();
    private final Map<String, Integer> materialIds = new HashMap<>();

    public Database(Path file) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("Cannot create database directory for " + file, e);
        }
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        // Instantiate the driver directly: DriverManager's caller-classloader checks don't play well with
        // NeoForge's module layers.
        org.sqlite.JDBC driver = new org.sqlite.JDBC();
        this.writeConnection = driver.connect(url, new Properties());
        this.readConnection = driver.connect(url, new Properties());

        try (Statement st = writeConnection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            for (String sql : SCHEMA) {
                if (sql.startsWith("ALTER TABLE") && columnExists(sql)) {
                    continue;
                }
                st.execute(sql);
            }
        }
        try (Statement st = readConnection.createStatement()) {
            st.execute("PRAGMA query_only=ON");
        }
    }

    /** For {@code ALTER TABLE <table> ADD COLUMN <column> ...}: whether the column is already there. */
    private boolean columnExists(String alterSql) throws SQLException {
        String[] words = alterSql.split("\\s+");
        String table = words[2];
        String column = words[5];
        try (PreparedStatement ps = writeConnection.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ---- Writer side ----

    /** Inserts a batch in one transaction. Writer thread only. */
    synchronized void insert(List<LoggedAction> actions) throws SQLException {
        writeConnection.setAutoCommit(false);
        try (PreparedStatement ps = writeConnection.prepareStatement("""
                INSERT INTO actions (time, actor, world, x, y, z, action, material, data, amount, nbt, contents, contents_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            for (LoggedAction a : actions) {
                ps.setLong(1, a.time());
                ps.setInt(2, actorId(a.actor()));
                ps.setInt(3, dictionaryId("worlds", worldIds, a.world()));
                ps.setInt(4, a.x());
                ps.setInt(5, a.y());
                ps.setInt(6, a.z());
                ps.setInt(7, a.type().id);
                ps.setInt(8, dictionaryId("materials", materialIds, a.material()));
                ps.setString(9, a.data());
                ps.setInt(10, a.amount());
                ps.setBytes(11, a.nbt());
                ps.setString(12, a.contents());
                ps.setInt(13, a.contentsCount());
                ps.addBatch();
            }
            ps.executeBatch();
            writeConnection.commit();
        } catch (SQLException e) {
            writeConnection.rollback();
            // Cached ids may refer to rows that were just rolled back
            actorIds.clear();
            actorNames.clear();
            worldIds.clear();
            materialIds.clear();
            throw e;
        } finally {
            writeConnection.setAutoCommit(true);
        }
    }

    private int actorId(Actor actor) throws SQLException {
        Integer id = actorIds.get(actor.key());
        if (id != null && actor.name().equals(actorNames.get(actor.key()))) {
            return id;
        }
        // Upsert keeps the latest name for players who renamed
        try (PreparedStatement ps = writeConnection.prepareStatement(
                "INSERT INTO actors (key, name) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET name = excluded.name RETURNING id")) {
            ps.setString(1, actor.key());
            ps.setString(2, actor.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getInt(1);
            }
        }
        actorIds.put(actor.key(), id);
        actorNames.put(actor.key(), actor.name());
        return id;
    }

    private int dictionaryId(String table, Map<String, Integer> cache, String name) throws SQLException {
        Integer id = cache.get(name);
        if (id != null) {
            return id;
        }
        try (PreparedStatement ps = writeConnection.prepareStatement(
                "INSERT INTO " + table + " (name) VALUES (?) ON CONFLICT (name) DO UPDATE SET name = name RETURNING id")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getInt(1);
            }
        }
        cache.put(name, id);
        return id;
    }

    /**
     * Sets the rolled-back flag. Called from the lookup thread; synchronized with {@link #insert} because both use
     * the write connection.
     */
    synchronized void markRolledBack(List<Long> ids, boolean rolledBack) throws SQLException {
        writeConnection.setAutoCommit(false);
        try (PreparedStatement ps = writeConnection.prepareStatement("UPDATE actions SET rolled_back = ? WHERE id = ?")) {
            for (long id : ids) {
                ps.setInt(1, rolledBack ? 1 : 0);
                ps.setLong(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
            writeConnection.commit();
        } catch (SQLException e) {
            writeConnection.rollback();
            throw e;
        } finally {
            writeConnection.setAutoCommit(true);
        }
    }

    /**
     * Deletes up to {@code limit} actions older than {@code cutoff}. Called from the lookup thread; small batches keep
     * the writer from waiting long on the shared write connection.
     *
     * @return rows deleted
     */
    synchronized int deleteOlderThan(long cutoff, int limit) throws SQLException {
        try (PreparedStatement ps = writeConnection.prepareStatement(
                "DELETE FROM actions WHERE id IN (SELECT id FROM actions WHERE time < ? LIMIT ?)")) {
            ps.setLong(1, cutoff);
            ps.setInt(2, limit);
            return ps.executeUpdate();
        }
    }

    /** Rebuilds the database file so space freed by a purge is returned to the disk. Blocks writes while it runs. */
    synchronized void vacuum() throws SQLException {
        try (Statement st = writeConnection.createStatement()) {
            st.execute("VACUUM");
        }
    }

    // ---- Reader side ----

    int countOlderThan(long cutoff) throws SQLException {
        try (PreparedStatement ps = readConnection.prepareStatement("SELECT COUNT(*) FROM actions WHERE time < ?")) {
            ps.setLong(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Actions to roll back ({@code rolledBack == false}, newest first) or to restore ({@code rolledBack == true},
     * oldest first). Lookup executor only.
     */
    List<StoredAction> fetchForRollback(LookupQuery query, boolean rolledBack, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = query.toSqlWhere(params) + " AND a.rolled_back = ?";
        params.add(rolledBack ? 1 : 0);
        List<StoredAction> rows = new ArrayList<>();
        try (PreparedStatement ps = readConnection.prepareStatement("""
                SELECT a.id, w.name, a.x, a.y, a.z, a.action, m.name, a.data, a.amount, a.nbt
                FROM actions a
                JOIN actors ac ON ac.id = a.actor
                JOIN worlds w ON w.id = a.world
                JOIN materials m ON m.id = a.material
                """ + where + " ORDER BY a.id " + (rolledBack ? "ASC" : "DESC") + " LIMIT ?")) {
            bind(ps, params);
            ps.setInt(params.size() + 1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new StoredAction(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                            ActionType.byId(rs.getInt(6)), rs.getString(7), rs.getString(8), rs.getInt(9),
                            rs.getBytes(10)));
                }
            }
        }
        return rows;
    }

    public record Page(List<LookupRow> rows, int total) {
    }

    /** Runs a lookup. Lookup executor only. */
    public Page lookup(LookupQuery query, int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = query.toSqlWhere(params);
        String from = """
                FROM actions a
                JOIN actors ac ON ac.id = a.actor
                JOIN worlds w ON w.id = a.world
                JOIN materials m ON m.id = a.material
                """;

        int total;
        try (PreparedStatement ps = readConnection.prepareStatement("SELECT COUNT(*) " + from + where)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getInt(1);
            }
        }

        List<LookupRow> rows = new ArrayList<>();
        try (PreparedStatement ps = readConnection.prepareStatement("""
                SELECT a.id, a.time, ac.name, w.name, a.x, a.y, a.z, a.action, m.name, a.data, a.amount,
                       a.contents, a.contents_count, a.rolled_back
                """ + from + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?")) {
            bind(ps, params);
            ps.setInt(params.size() + 1, limit);
            ps.setInt(params.size() + 2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LookupRow(
                            rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getInt(6), rs.getInt(7),
                            ActionType.byId(rs.getInt(8)), rs.getString(9), rs.getString(10),
                            rs.getInt(11), rs.getString(12), rs.getInt(13), rs.getInt(14) != 0));
                }
            }
        }
        return new Page(rows, total);
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            readConnection.close();
        } finally {
            writeConnection.close();
        }
    }
}
