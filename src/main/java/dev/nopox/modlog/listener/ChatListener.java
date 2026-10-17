package dev.nopox.modlog.listener;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.action.Actor;
import dev.nopox.modlog.action.LoggedAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

/** Logs chat messages and commands of players, for lookups only. The text goes in the {@code data} column. */
public final class ChatListener {
    /** Material ids recorded for these actions; they double as their icons in the GUI. */
    private static final String CHAT_MATERIAL = "minecraft:writable_book";
    private static final String COMMAND_MATERIAL = "minecraft:command_block";

    private ChatListener() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, ChatListener::onChat);
        bus.addListener(EventPriority.LOWEST, ChatListener::onCommand);
    }

    private static void onChat(ServerChatEvent event) {
        if (ModLogConfig.LOG_CHAT.get()) {
            log(event.getPlayer(), ActionType.CHAT, CHAT_MATERIAL, event.getRawText());
        }
    }

    private static void onCommand(CommandEvent event) {
        if (ModLogConfig.LOG_COMMANDS.get()
                && event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player) {
            log(player, ActionType.COMMAND, COMMAND_MATERIAL, "/" + event.getParseResults().getReader().getString());
        }
    }

    private static void log(ServerPlayer player, ActionType type, String material, String text) {
        BlockPos pos = player.blockPosition();
        ModLog.log(new LoggedAction(System.currentTimeMillis(), Actor.of(player), player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(), type, material, text, 1, null, null, 0));
    }
}
