package dev.nopox.modlog.container;

import net.minecraft.world.item.ItemStack;

/** Hash key for "same item, same data components", ignoring count. */
public final class ItemKey {
    private final ItemStack stack;
    private final int hash;

    public ItemKey(ItemStack stack) {
        this.stack = stack.copyWithCount(1);
        this.hash = ItemStack.hashItemAndComponents(this.stack);
    }

    /** A single-item copy of the stack. */
    public ItemStack stack() {
        return stack;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ItemKey other && hash == other.hash && ItemStack.isSameItemSameComponents(stack, other.stack);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
