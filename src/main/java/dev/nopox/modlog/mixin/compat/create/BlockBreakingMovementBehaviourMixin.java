package dev.nopox.modlog.mixin.compat.create;

import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import dev.nopox.modlog.action.Actor;
import dev.nopox.modlog.listener.BlockListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs blocks broken by drills and saws on moving Create contraptions. */
@Mixin(BlockBreakingMovementBehaviour.class)
public abstract class BlockBreakingMovementBehaviourMixin {
    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void modlog$logBreak(MovementContext context, BlockPos breakingPos, CallbackInfo ci) {
        if (context.world instanceof ServerLevel level) {
            // Named after the machine doing it, e.g. #create:mechanical_drill
            String machine = BuiltInRegistries.BLOCK.getKey(context.state.getBlock()).toString();
            BlockListener.breaking(level, breakingPos.immutable(), level.getBlockState(breakingPos), Actor.nonPlayer(machine));
        }
    }
}
