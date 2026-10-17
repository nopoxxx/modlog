package dev.nopox.modlog.container;

import dev.nopox.modlog.util.Ids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Access to block inventories through NeoForge's item capability, which vanilla and modded blocks expose alike. */
public final class ContainerContents {
    /** Summaries list at most this many item kinds. */
    private static final int MAX_KINDS = 8;

    public record Summary(String text, int total) {
    }

    private ContainerContents() {
    }

    /** The block's item handler for the unsided query, else for any side. */
    public static @Nullable IItemHandler itemHandler(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            return handler;
        }
        for (Direction side : Direction.values()) {
            handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    /** What's inside the block, or null if it has no inventory or it's empty. */
    public static @Nullable Summary summarize(ServerLevel level, BlockPos pos) {
        IItemHandler handler = itemHandler(level, pos);
        if (handler == null) {
            return null;
        }
        Map<Item, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                total += stack.getCount();
            }
        }
        if (total == 0) {
            return null;
        }
        String text = counts.entrySet().stream()
                .sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
                .limit(MAX_KINDS)
                .map(e -> e.getValue() + " " + Ids.shortId(BuiltInRegistries.ITEM.getKey(e.getKey()).toString()))
                .collect(Collectors.joining(", "));
        if (counts.size() > MAX_KINDS) {
            text += ", +" + (counts.size() - MAX_KINDS) + " more kinds";
        }
        return new Summary(text, total);
    }
}
