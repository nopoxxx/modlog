package dev.nopox.modlog.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.PlayerInvWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A player's own inventory as an item storage ({@code player:<uuid>}), e.g. for items they picked up. */
final class PlayerInventories implements ItemStorages.Provider {
    static final String PREFIX = "player:";

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public @Nullable String identify(AbstractContainerMenu menu) {
        return null;
    }

    @Override
    public @Nullable IItemHandler find(MinecraftServer server, String marker) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(marker.substring(PREFIX.length())));
            return player == null ? null : new PlayerInvWrapper(player.getInventory());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean isHolder(ItemStack stack, String marker) {
        return false;
    }

    @Override
    public String describe(String marker) {
        return "player inventory";
    }
}
