package dev.nopox.modlog.compat;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.inventory.IMekanismInventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Mekanism. Only loaded when Mekanism is installed. */
final class MekanismCompat {
    private MekanismCompat() {
    }

    static @Nullable IItemHandler inventory(BlockEntity be) {
        if (!(be instanceof IMekanismInventory inventory) || !inventory.hasInventory()) {
            return null;
        }
        // All slots regardless of side, with the rules a player in the GUI has
        return new SlotsHandler(inventory.getInventorySlots(null));
    }

    private record SlotsHandler(List<IInventorySlot> slots) implements IItemHandler {
        @Override
        public int getSlots() {
            return slots.size();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slots.get(slot).getStack();
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return slots.get(slot).insertItem(stack, Action.get(!simulate), AutomationType.MANUAL);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return slots.get(slot).extractItem(amount, Action.get(!simulate), AutomationType.MANUAL);
        }

        @Override
        public int getSlotLimit(int slot) {
            return slots.get(slot).getLimit(ItemStack.EMPTY);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slots.get(slot).isItemValid(stack);
        }
    }
}
