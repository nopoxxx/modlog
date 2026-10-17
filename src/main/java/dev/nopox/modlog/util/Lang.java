package dev.nopox.modlog.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side translations. Clients don't have ModLog's language files, so text is translated here: players get their
 * client's language, the console the configured one, English as the fallback. Files are
 * {@code assets/modlog/lang/<language>.json} in the mod jar, values are {@link String#format} patterns.
 */
public final class Lang {
    public static final String FALLBACK = "en_us";
    private static final String[] BUNDLED = {"en_us", "ru_ru"};

    private static final Map<String, Map<String, String>> LANGUAGES = new HashMap<>();

    private Lang() {
    }

    public static void init() {
        for (String language : BUNDLED) {
            String path = "/assets/" + ModLog.MOD_ID + "/lang/" + language + ".json";
            try (InputStream in = Lang.class.getResourceAsStream(path)) {
                if (in == null) {
                    ModLog.LOGGER.warn("Missing language file {}", path);
                    continue;
                }
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, String> strings = new HashMap<>();
                json.entrySet().forEach(e -> strings.put(e.getKey(), e.getValue().getAsString()));
                LANGUAGES.put(language, strings);
            } catch (Exception e) {
                ModLog.LOGGER.error("Failed to load language file {}", path, e);
            }
        }
    }

    /** The player's client language if ModLog has it, else the configured one. */
    public static String of(@Nullable ServerPlayer player) {
        if (player != null) {
            String client = player.clientInformation().language().toLowerCase(Locale.ROOT);
            if (LANGUAGES.containsKey(client)) {
                return client;
            }
        }
        String configured = ModLogConfig.LANGUAGE.get().toLowerCase(Locale.ROOT);
        return LANGUAGES.containsKey(configured) ? configured : FALLBACK;
    }

    public static String of(CommandSourceStack source) {
        return of(source.getEntity() instanceof ServerPlayer player ? player : null);
    }

    public static String tr(String language, String key, Object... args) {
        String pattern = LANGUAGES.getOrDefault(language, Map.of()).get(key);
        if (pattern == null) {
            pattern = LANGUAGES.getOrDefault(FALLBACK, Map.of()).getOrDefault(key, key);
        }
        if (args.length == 0) {
            return pattern;
        }
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (IllegalFormatException e) {
            ModLog.LOGGER.warn("Bad translation for {} in {}: {}", key, language, pattern);
            return pattern;
        }
    }

    public static String tr(CommandSourceStack source, String key, Object... args) {
        return tr(of(source), key, args);
    }

    public static String tr(ServerPlayer player, String key, Object... args) {
        return tr(of(player), key, args);
    }

    public static MutableComponent text(CommandSourceStack source, String key, Object... args) {
        return Component.literal(tr(source, key, args));
    }

    public static MutableComponent text(ServerPlayer player, String key, Object... args) {
        return Component.literal(tr(player, key, args));
    }

    public static MutableComponent text(String language, String key, Object... args) {
        return Component.literal(tr(language, key, args));
    }
}
