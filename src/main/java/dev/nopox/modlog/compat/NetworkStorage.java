package dev.nopox.modlog.compat;

import net.minecraft.world.item.ItemStack;

/** A storage network (AE2 ME network, RS network) seen as one big inventory. */
public interface NetworkStorage {
    /** @return how many were inserted */
    long insert(ItemStack template, long amount);

    /** @return how many were extracted */
    long extract(ItemStack template, long amount);
}
