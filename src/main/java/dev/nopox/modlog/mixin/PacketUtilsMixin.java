package dev.nopox.modlog.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.nopox.modlog.container.ContainerTracker;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Wraps the main-thread handling of vanilla packets so container changes can be attributed to them. */
@Mixin(PacketUtils.class)
public abstract class PacketUtilsMixin {
    @WrapOperation(
            method = "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/BlockableEventLoop;executeIfPossible(Ljava/lang/Runnable;)V"))
    private static void modlog$trackPacket(BlockableEventLoop<?> executor, Runnable task, Operation<Void> original,
                                           @Local(argsOnly = true) Packet<?> packet,
                                           @Local(argsOnly = true) PacketListener listener) {
        original.call(executor, ContainerTracker.wrapPacket(packet, listener, task));
    }
}
