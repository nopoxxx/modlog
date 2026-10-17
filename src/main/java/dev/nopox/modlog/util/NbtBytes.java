package dev.nopox.modlog.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class NbtBytes {
    private NbtBytes() {
    }

    public static byte[] compress(CompoundTag tag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            NbtIo.writeCompressed(tag, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static CompoundTag decompress(byte[] bytes) {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Full block entity data (including id and position), or null if there is no block entity. */
    public static byte @Nullable [] blockEntity(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : compress(be.saveWithFullMetadata(level.registryAccess()));
    }
}
