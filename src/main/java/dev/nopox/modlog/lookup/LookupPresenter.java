package dev.nopox.modlog.lookup;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.compat.ItemStorages;
import dev.nopox.modlog.compat.NetworkStorages;
import dev.nopox.modlog.db.Database;
import dev.nopox.modlog.db.LookupQuery;
import dev.nopox.modlog.db.LookupRow;
import dev.nopox.modlog.db.Storage;
import dev.nopox.modlog.util.Ids;
import dev.nopox.modlog.util.TimeFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Runs lookups off-thread and prints paged results; remembers each source's last query for {@code /ml page}. */
public final class LookupPresenter {
    private record Last(LookupQuery query, boolean inspector) {
    }

    private static final Map<String, Last> LAST_QUERY = new ConcurrentHashMap<>();

    private LookupPresenter() {
    }

    public static void show(CommandSourceStack source, LookupQuery query, boolean inspector, int page) {
        LAST_QUERY.put(sourceKey(source), new Last(query, inspector));
        run(source, query, inspector, page);
    }

    /** @return false if the source has no previous lookup */
    public static boolean showPage(CommandSourceStack source, int page) {
        Last last = LAST_QUERY.get(sourceKey(source));
        if (last == null) {
            return false;
        }
        run(source, last.query, last.inspector, page);
        return true;
    }

    public static void forget(String sourceKey) {
        LAST_QUERY.remove(sourceKey);
    }

    public static String sourceKey(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getStringUUID() : source.getTextName();
    }

    private static void run(CommandSourceStack source, LookupQuery query, boolean inspector, int page) {
        Storage storage = ModLog.storage();
        if (storage == null) {
            source.sendFailure(Component.literal("ModLog database is not available, see server log"));
            return;
        }
        int pageSize = ModLogConfig.PAGE_SIZE.get();
        MinecraftServer server = source.getServer();
        storage.lookup(query, (page - 1) * pageSize, pageSize).whenComplete((result, error) -> server.execute(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                ModLog.LOGGER.error("Lookup failed", cause);
                source.sendFailure(Component.literal("Lookup failed: " + cause.getMessage()));
            } else {
                print(source, query, inspector, page, pageSize, result);
            }
        }));
    }

    private static void print(CommandSourceStack source, LookupQuery query, boolean inspector, int page, int pageSize,
                              Database.Page result) {
        MutableComponent header = Component.literal("----- ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("ModLog").withStyle(ChatFormatting.DARK_AQUA))
                .append(" -----");
        if (inspector && query.center() != null) {
            header.append(Component.literal(" (%d %d %d)".formatted(
                    query.center().getX(), query.center().getY(), query.center().getZ())).withStyle(ChatFormatting.GRAY));
        }
        source.sendSystemMessage(header);

        if (result.total() == 0) {
            source.sendSystemMessage(Component.literal("No data found.").withStyle(ChatFormatting.GRAY));
            return;
        }
        int pages = (result.total() + pageSize - 1) / pageSize;
        if (result.rows().isEmpty()) {
            source.sendSystemMessage(Component.literal("Page %d doesn't exist (%d pages).".formatted(page, pages))
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        long now = System.currentTimeMillis();
        for (LookupRow row : result.rows()) {
            source.sendSystemMessage(formatRow(row, now, !inspector));
        }
        if (pages > 1) {
            source.sendSystemMessage(pageFooter(page, pages));
        }
    }

    private static Component formatRow(LookupRow row, long now, boolean withLocation) {
        Style rowStyle = row.rolledBack() ? Style.EMPTY.withStrikethrough(true) : Style.EMPTY;
        MutableComponent line = Component.literal(TimeFormat.ago(now - row.time()))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" - ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(row.actor()).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(" " + row.type().verb + " ").withStyle(ChatFormatting.WHITE));

        String item = Ids.shortId(row.material());
        if (row.amount() != 1) {
            item = row.amount() + " x " + item;
        }
        String hover = row.material();
        if (NetworkStorages.DATA_MARKER.equals(row.data())) {
            hover += "\nvia storage network terminal";
        } else if (ItemStorages.isMarker(row.data())) {
            hover += "\nin " + ItemStorages.describe(row.data());
        } else if (row.data() != null) {
            hover += row.data();
        }
        line.append(Component.literal(item).withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover)))));
        if (row.contentsCount() > 0 && row.contents() != null) {
            line.append(Component.literal(" [%d items]".formatted(row.contentsCount()))
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(row.contents())))));
        }
        line.withStyle(rowStyle);

        if (withLocation) {
            String tp = "/execute in %s run tp @s %d %d %d".formatted(row.world(), row.x(), row.y() + 1, row.z());
            line.append(Component.literal(" (%d %d %d %s)".formatted(row.x(), row.y(), row.z(), Ids.shortId(row.world())))
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, tp))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Teleport")))));
        }
        return line;
    }

    private static Component pageFooter(int page, int pages) {
        MutableComponent footer = Component.literal("");
        if (page > 1) {
            footer.append(pageLink("◀ ", page - 1));
        }
        footer.append(Component.literal("Page %d/%d".formatted(page, pages)).withStyle(ChatFormatting.WHITE));
        if (page < pages) {
            footer.append(pageLink(" ▶", page + 1));
        }
        return footer;
    }

    private static Component pageLink(String text, int page) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ml page " + page))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Page " + page))));
    }
}
