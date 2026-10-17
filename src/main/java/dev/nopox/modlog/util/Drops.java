package dev.nopox.modlog.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What fell out of a broken block: each item entity's stack as it spawned and the entity's UUID, so rollback can
 * remove the item from the ground if it's still lying there.
 */
public record Drops(List<Drop> drops) {
    public record Drop(ItemStack stack, UUID entity) {
    }

    public static byte @Nullable [] encode(List<Drop> drops, HolderLookup.Provider registries) {
        if (drops.isEmpty()) {
            return null;
        }
        ListTag list = new ListTag();
        for (Drop drop : drops) {
            if (drop.stack.isEmpty()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.put("item", drop.stack.save(registries));
            entry.put("entity", NbtUtils.createUUID(drop.entity));
            list.add(entry);
        }
        CompoundTag root = new CompoundTag();
        root.put("drops", list);
        return NbtBytes.compress(root);
    }

    public static Drops decode(byte @Nullable [] bytes, HolderLookup.Provider registries) {
        List<Drop> drops = new ArrayList<>();
        if (bytes != null) {
            for (Tag tag : NbtBytes.decompress(bytes).getList("drops", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) tag;
                ItemStack stack = ItemStack.parseOptional(registries, entry.getCompound("item"));
                if (!stack.isEmpty()) {
                    drops.add(new Drop(stack, NbtUtils.loadUUID(entry.get("entity"))));
                }
            }
        }
        return new Drops(drops);
    }
}
