package dev.nopox.modlog;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModLogConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> SQLITE_FILE;
    public static final ModConfigSpec.IntValue FLUSH_INTERVAL_MS;
    public static final ModConfigSpec.IntValue PAGE_SIZE;

    public static final ModConfigSpec.IntValue ROLLBACK_PER_TICK;
    public static final ModConfigSpec.IntValue ROLLBACK_MAX_ACTIONS;
    public static final ModConfigSpec.BooleanValue ROLLBACK_RECLAIM;

    public static final ModConfigSpec.BooleanValue LOG_BLOCK_BREAK;
    public static final ModConfigSpec.BooleanValue LOG_BLOCK_PLACE;
    public static final ModConfigSpec.BooleanValue LOG_EXPLOSIONS;
    public static final ModConfigSpec.BooleanValue LOG_CONTAINERS;
    public static final ModConfigSpec.BooleanValue LOG_ITEM_DROPS;
    public static final ModConfigSpec.BooleanValue LOG_ITEM_PICKUPS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("database");
        SQLITE_FILE = b.comment("SQLite database file, relative to the server directory")
                .define("sqliteFile", "modlog/modlog.db");
        FLUSH_INTERVAL_MS = b.comment("How often queued actions are written to the database, in milliseconds")
                .defineInRange("flushIntervalMs", 500, 50, 10_000);
        b.pop();

        b.push("lookup");
        PAGE_SIZE = b.comment("Lines per page in lookup and inspector output")
                .defineInRange("pageSize", 7, 1, 50);
        b.pop();

        b.push("rollback");
        ROLLBACK_PER_TICK = b.comment("Actions applied per server tick during rollback/restore; lower it if rollbacks lag the server")
                .defineInRange("actionsPerTick", 500, 1, 100_000);
        ROLLBACK_MAX_ACTIONS = b.comment("A rollback/restore matching more actions than this is refused; narrow it with u:, t:, r:")
                .defineInRange("maxActions", 200_000, 1, 10_000_000);
        ROLLBACK_RECLAIM = b.comment("Take back what rolled-back actions gave players (items taken from containers, drops of",
                        "broken blocks), from the ground, their inventory or wherever the rollback already moved them, so",
                        "rollbacks don't duplicate items; anything not found is reported")
                .define("reclaimItems", true);
        b.pop();

        b.push("logging");
        LOG_BLOCK_BREAK = b.define("blockBreak", true);
        LOG_BLOCK_PLACE = b.define("blockPlace", true);
        LOG_EXPLOSIONS = b.comment("Blocks destroyed by explosions")
                .define("explosions", true);
        LOG_CONTAINERS = b.comment("Items players put into or take out of block inventories (any mod, incl. AE2/RS terminals)")
                .define("containers", true);
        LOG_ITEM_DROPS = b.comment("Items players drop on the ground")
                .define("itemDrops", true);
        LOG_ITEM_PICKUPS = b.comment("Items players pick up from the ground")
                .define("itemPickups", true);
        b.pop();

        SPEC = b.build();
    }

    private ModLogConfig() {
    }
}
