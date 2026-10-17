package dev.nopox.modlog.listener;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.action.Actor;
import dev.nopox.modlog.action.LoggedAction;
import dev.nopox.modlog.util.NbtBytes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Logs sign text edits by players, keeping the text before and after for rollback. Called from the sign mixin. */
public final class SignListener {
    private SignListener() {
    }

    /** The sign's text data, or null if nobody is editing it (dye, glow ink, wax, commands). */
    public static @Nullable CompoundTag snapshot(SignBlockEntity sign) {
        if (!ModLogConfig.LOG_SIGNS.get() || !(sign.getLevel() instanceof ServerLevel level) || editor(sign, level) == null) {
            return null;
        }
        return sign.saveWithoutMetadata(level.registryAccess());
    }

    public static void edited(SignBlockEntity sign, CompoundTag before) {
        if (!(sign.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Player player = editor(sign, level);
        CompoundTag after = sign.saveWithoutMetadata(level.registryAccess());
        if (player == null || after.equals(before)) {
            return;
        }
        CompoundTag both = new CompoundTag();
        both.put("before", before);
        both.put("after", after);
        BlockPos pos = sign.getBlockPos();
        ModLog.log(new LoggedAction(System.currentTimeMillis(), Actor.of(player), level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(), ActionType.SIGN_EDIT,
                BuiltInRegistries.BLOCK.getKey(sign.getBlockState().getBlock()).toString(),
                text(sign.getFrontText(), sign.getBackText()), 1, NbtBytes.compress(both), null, 0));
    }

    private static @Nullable Player editor(SignBlockEntity sign, ServerLevel level) {
        UUID uuid = sign.getPlayerWhoMayEdit();
        return uuid == null ? null : level.getPlayerByUUID(uuid);
    }

    /** Readable text, front and back: {@code line1 / line2 | back1}. */
    private static String text(SignText front, SignText back) {
        String f = lines(front);
        String b = lines(back);
        return b.isEmpty() ? f : f + " | " + b;
    }

    private static String lines(SignText text) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String line = text.getMessage(i, false).getString().trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return String.join(" / ", lines);
    }
}
