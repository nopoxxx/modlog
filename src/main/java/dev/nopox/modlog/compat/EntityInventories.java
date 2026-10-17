package dev.nopox.modlog.compat;

import dev.nopox.modlog.util.Ids;
import dev.nopox.modlog.util.Lang;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Item frames and armor stands as item storages ({@code entity:<uuid>}), so items players put on or take off them
 * are logged and rolled back like container transactions.
 */
public final class EntityInventories implements ItemStorages.Provider {
    static final String PREFIX = "entity:";

    /** Whether the entity holds items this way. */
    public static boolean holdsItems(Entity entity) {
        return entity instanceof ItemFrame || entity instanceof ArmorStand;
    }

    /** {@code entity:<uuid>|<type>}; the type is only for display. */
    public static String marker(Entity entity) {
        return PREFIX + entity.getUUID() + "|" + EntityType.getKey(entity.getType());
    }

    /** The entity's items as an item handler, or null if it doesn't hold items. */
    public static @Nullable IItemHandler of(Entity entity) {
        if (entity instanceof ItemFrame frame) {
            return new Slots(1) {
                @Override
                public ItemStack getStackInSlot(int slot) {
                    return frame.getItem();
                }

                @Override
                public void setStackInSlot(int slot, ItemStack stack) {
                    frame.setItem(stack);
                }
            };
        }
        if (entity instanceof ArmorStand stand) {
            EquipmentSlot[] slots = EquipmentSlot.values();
            return new Slots(slots.length) {
                @Override
                public ItemStack getStackInSlot(int slot) {
                    return stand.getItemBySlot(slots[slot]);
                }

                @Override
                public void setStackInSlot(int slot, ItemStack stack) {
                    stand.setItemSlot(slots[slot], stack);
                }
            };
        }
        return null;
    }

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
        UUID uuid;
        try {
            int end = marker.indexOf('|');
            uuid = UUID.fromString(marker.substring(PREFIX.length(), end < 0 ? marker.length() : end));
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null && entity.isAlive()) {
                return of(entity);
            }
        }
        return null;
    }

    @Override
    public boolean isHolder(ItemStack stack, String marker) {
        return false;
    }

    @Override
    public String describe(String language, String marker) {
        int bar = marker.indexOf('|');
        return bar < 0 ? Lang.tr(language, "where.entity") : Ids.shortId(marker.substring(bar + 1));
    }

    /**
     * Single-item slots over an entity's own getters/setters. Every slot holds one stack of up to its max size; inserting
     * only fills empty slots or tops up the same item.
     */
    private abstract static class Slots implements IItemHandlerModifiable {
        private final int count;

        Slots(int count) {
            this.count = count;
        }

        @Override
        public int getSlots() {
            return count;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            ItemStack current = getStackInSlot(slot);
            if (stack.isEmpty() || (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, stack))) {
                return stack;
            }
            int room = Math.min(getSlotLimit(slot), stack.getMaxStackSize()) - current.getCount();
            int moved = Math.min(room, stack.getCount());
            if (moved <= 0) {
                return stack;
            }
            if (!simulate) {
                setStackInSlot(slot, stack.copyWithCount(current.getCount() + moved));
            }
            return stack.copyWithCount(stack.getCount() - moved);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack current = getStackInSlot(slot);
            int moved = Math.min(amount, current.getCount());
            if (moved <= 0) {
                return ItemStack.EMPTY;
            }
            if (!simulate) {
                setStackInSlot(slot, moved == current.getCount() ? ItemStack.EMPTY : current.copyWithCount(current.getCount() - moved));
            }
            return current.copyWithCount(moved);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return true;
        }
    }
}
