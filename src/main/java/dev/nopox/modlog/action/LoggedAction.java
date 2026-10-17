package dev.nopox.modlog.action;

import org.jetbrains.annotations.Nullable;

/**
 * One action queued for writing.
 *
 * @param time     epoch millis
 * @param world    dimension id, e.g. {@code minecraft:overworld}
 * @param material block or item registry id
 * @param data     block state properties ({@code [facing=north]}) for blocks; unused for items
 * @param amount   item count for container actions, 1 for blocks
 * @param nbt      compressed NBT: block entity data for blocks, the stack (count 1) for items
 * @param contents for broken containers: human-readable summary of what was inside, e.g. {@code 64 stone, 3 ae2:fluix_crystal}
 * @param contentsCount total number of items inside, 0 if none
 */
public record LoggedAction(
        long time,
        Actor actor,
        String world,
        int x, int y, int z,
        ActionType type,
        String material,
        @Nullable String data,
        int amount,
        byte @Nullable [] nbt,
        @Nullable String contents,
        int contentsCount
) {
}
