package dev.nopox.modlog.rollback;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.container.ItemKey;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Takes items from and gives items to the saved inventory of a player who is offline, by editing their
 * {@code playerdata/<uuid>.dat} the way the server saves it (temp file, keeping {@code .dat_old}). Only valid while the
 * player is offline: the server reads the file on login. Main thread only.
 */
final class OfflineInventories {
    /** Main inventory slots (hotbar and the 27 above it) in the saved data. */
    private static final int MAIN_SLOTS = 36;

    private OfflineInventories() {
    }

    /** @return how many were taken */
    static int take(MinecraftServer server, UUID player, ItemKey key, int wanted) {
        CompoundTag data = read(server, player);
        if (data == null || wanted <= 0) {
            return 0;
        }
        ListTag inventory = data.getList("Inventory", Tag.TAG_COMPOUND);
        int taken = 0;
        for (int i = 0; i < inventory.size() && taken < wanted; i++) {
            CompoundTag entry = inventory.getCompound(i);
            ItemStack stack = ItemStack.parseOptional(server.registryAccess(), entry);
            if (stack.isEmpty() || !key.equals(new ItemKey(stack))) {
                continue;
            }
            int take = Math.min(wanted - taken, stack.getCount());
            stack.shrink(take);
            taken += take;
            if (stack.isEmpty()) {
                inventory.remove(i--);
            } else {
                inventory.set(i, withSlot(server, stack, entry.getByte("Slot")));
            }
        }
        if (taken > 0 && !write(server, player, data, inventory)) {
            return 0;
        }
        return taken;
    }

    /** Puts items into free main inventory slots. @return what didn't fit (or couldn't be written), to drop instead */
    static ItemStack give(MinecraftServer server, UUID player, ItemStack stack) {
        CompoundTag data = read(server, player);
        if (data == null) {
            return stack;
        }
        ListTag inventory = data.getList("Inventory", Tag.TAG_COMPOUND);
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < inventory.size(); i++) {
            used.add(inventory.getCompound(i).getByte("Slot") & 0xFF);
        }
        ItemStack left = stack.copy();
        for (int slot = 0; slot < MAIN_SLOTS && !left.isEmpty(); slot++) {
            if (used.add(slot)) {
                inventory.add(withSlot(server, left.split(left.getMaxStackSize()), (byte) slot));
            }
        }
        if (left.getCount() != stack.getCount() && !write(server, player, data, inventory)) {
            return stack;
        }
        return left;
    }

    private static CompoundTag withSlot(MinecraftServer server, ItemStack stack, byte slot) {
        CompoundTag tag = (CompoundTag) stack.save(server.registryAccess(), new CompoundTag());
        tag.putByte("Slot", slot);
        return tag;
    }

    private static Path file(MinecraftServer server, UUID player) {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat");
    }

    private static @Nullable CompoundTag read(MinecraftServer server, UUID player) {
        Path file = file(server, player);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            ModLog.LOGGER.warn("Can't read the saved inventory of offline player {}", player, e);
            return null;
        }
    }

    private static boolean write(MinecraftServer server, UUID player, CompoundTag data, ListTag inventory) {
        data.put("Inventory", inventory);
        Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        try {
            Path temp = Files.createTempFile(dir, player + "-", ".dat");
            NbtIo.writeCompressed(data, temp);
            Util.safeReplaceFile(file(server, player), temp, dir.resolve(player + ".dat_old"));
            return true;
        } catch (IOException e) {
            ModLog.LOGGER.error("Can't save the inventory of offline player {}", player, e);
            return false;
        }
    }
}
