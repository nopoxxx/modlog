package dev.nopox.modlog.mixin.compat.create;

import com.simibubi.create.content.kinetics.base.BlockBreakingKineticBlockEntity;
import dev.nopox.modlog.action.Actor;
import dev.nopox.modlog.listener.BlockListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs blocks broken by Create's mechanical drill and saw, which break blocks without a player or break event. */
@Mixin(BlockBreakingKineticBlockEntity.class)
public abstract class BlockBreakingKineticBlockEntityMixin {
    @Shadow
    protected BlockPos breakingPos;

    @Inject(method = "onBlockBroken", at = @At("HEAD"))
    private void modlog$logBreak(BlockState stateToBreak, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getLevel() instanceof ServerLevel level && breakingPos != null) {
            String machine = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(self.getType()).toString();
            BlockListener.breaking(level, breakingPos.immutable(), stateToBreak, Actor.nonPlayer(machine));
        }
    }
}
