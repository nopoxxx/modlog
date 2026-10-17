package dev.nopox.modlog.lookup;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.compat.ItemStorages;
import dev.nopox.modlog.compat.NetworkStorages;
import dev.nopox.modlog.db.Database;
import dev.nopox.modlog.db.LookupQuery;
import dev.nopox.modlog.db.LookupRow;
import dev.nopox.modlog.db.Storage;
import dev.nopox.modlog.util.Ids;
import dev.nopox.modlog.util.Lang;
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
            source.sendFailure(Lang.text(source, "error.database_unavailable"));
            return;
        }
        int pageSize = ModLogConfig.PAGE_SIZE.get();
        MinecraftServer server = source.getServer();
        storage.lookup(query, (page - 1) * pageSize, pageSize).whenComplete((result, error) -> server.execute(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                ModLog.LOGGER.error("Lookup failed", cause);
                source.sendFailure(Lang.text(source, "error.lookup_failed", cause.getMessage()));
            } else {
                print(source, query, inspector, page, pageSize, result);
            }
        }));
    }

    private static void print(CommandSourceStack source, LookupQuery query, boolean inspector, int page, int pageSize,
                              Database.Page result) {
        String lang = Lang.of(source);
        MutableComponent header = Component.literal("----- ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("ModLog").withStyle(ChatFormatting.DARK_AQUA))
                .append(" -----");
        if (inspector && query.center() != null) {
            header.append(Component.literal(" (%d %d %d)".formatted(
                    query.center().getX(), query.center().getY(), query.center().getZ())).withStyle(ChatFormatting.GRAY));
        }
        source.sendSystemMessage(header);

        if (result.total() == 0) {
            source.sendSystemMessage(Lang.text(lang, "lookup.no_data").withStyle(ChatFormatting.GRAY));
            return;
        }
        int pages = (result.total() + pageSize - 1) / pageSize;
        if (result.rows().isEmpty()) {
            source.sendSystemMessage(Lang.text(lang, "lookup.no_such_page", page, pages).withStyle(ChatFormatting.GRAY));
            return;
        }

        long now = System.currentTimeMillis();
        for (LookupRow row : result.rows()) {
            source.sendSystemMessage(formatRow(lang, row, now, !inspector));
        }
        if (pages > 1) {
            source.sendSystemMessage(pageFooter(lang, page, pages));
        }
    }

    private static Component formatRow(String lang, LookupRow row, long now, boolean withLocation) {
        Style rowStyle = row.rolledBack() ? Style.EMPTY.withStrikethrough(true) : Style.EMPTY;
        MutableComponent line = Component.literal(TimeFormat.ago(lang, now - row.time()))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" - ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(row.actor()).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(" " + Lang.tr(lang, row.type().verbKey()) + " ").withStyle(ChatFormatting.WHITE));

        String item = RowText.subject(row);
        String hover = row.material();
        String details = RowText.details(lang, row);
        if (details != null) {
            hover += "\n" + details;
        } else if (NetworkStorages.DATA_MARKER.equals(row.data())) {
            hover += "\n" + Lang.tr(lang, "detail.via_network");
        } else if (ItemStorages.isMarker(row.data())) {
            hover += "\n" + Lang.tr(lang, "detail.in", ItemStorages.describe(lang, row.data()));
        } else if (row.data() != null && (row.type() == ActionType.BLOCK_BREAK || row.type() == ActionType.BLOCK_PLACE)) {
            hover += row.data();
        }
        line.append(Component.literal(item).withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover)))));
        String where = RowText.where(lang, row);
        if (where != null) {
            line.append(Component.literal(" → " + where).withStyle(ChatFormatting.GRAY));
        }
        if (row.contentsCount() > 0 && row.contents() != null) {
            line.append(Component.literal(" " + Lang.tr(lang, "lookup.contents", row.contentsCount()))
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(row.contents())))));
        }
        line.withStyle(rowStyle);

        if (withLocation) {
            String tp = "/execute in %s run tp @s %d %d %d".formatted(row.world(), row.x(), row.y() + 1, row.z());
            line.append(Component.literal(" (%d %d %d %s)".formatted(row.x(), row.y(), row.z(), Ids.shortId(row.world())))
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, tp))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Lang.text(lang, "lookup.teleport")))));
        }
        return line;
    }

    private static Component pageFooter(String lang, int page, int pages) {
        MutableComponent footer = Component.literal("");
        if (page > 1) {
            footer.append(pageLink(lang, "◀ ", page - 1));
        }
        footer.append(Lang.text(lang, "lookup.page", page, pages).withStyle(ChatFormatting.WHITE));
        if (page < pages) {
            footer.append(pageLink(lang, " ▶", page + 1));
        }
        return footer;
    }

    private static Component pageLink(String lang, String text, int page) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ml page " + page))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Lang.text(lang, "lookup.page_link", page))));
    }
}
