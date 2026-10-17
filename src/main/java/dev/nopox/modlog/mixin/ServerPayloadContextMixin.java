package dev.nopox.modlog.mixin;

import dev.nopox.modlog.container.ContainerTracker;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Supplier;

/**
 * Wraps main-thread work of modded payloads (AE2/RS terminal clicks, JEI transfers, ...). Both the default
 * main-thread handlers and handlers that enqueue work themselves end up here.
 */
@Mixin(ServerPayloadContext.class)
public abstract class ServerPayloadContextMixin {
    @ModifyVariable(method = "enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), argsOnly = true)
    private Runnable modlog$trackRunnable(Runnable task) {
        return ((ServerPayloadContext) (Object) this).listener() instanceof ServerGamePacketListenerImpl game
                ? ContainerTracker.wrapPayload(game, task)
                : task;
    }

    @ModifyVariable(method = "enqueueWork(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), argsOnly = true)
    private Supplier<?> modlog$trackSupplier(Supplier<?> task) {
        return ((ServerPayloadContext) (Object) this).listener() instanceof ServerGamePacketListenerImpl game
                ? ContainerTracker.wrapPayload(game, task)
                : task;
    }
}
