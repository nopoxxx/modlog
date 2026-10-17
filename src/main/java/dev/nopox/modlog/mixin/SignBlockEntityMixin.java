package dev.nopox.modlog.mixin;

import dev.nopox.modlog.listener.SignListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.UnaryOperator;

/**
 * Logs sign text edits. Hooked on {@code updateText}, which both vanilla's and Arclight's (overwritten)
 * {@code updateSignText} go through while the editing player is still recorded on the sign.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {
    @Unique
    private CompoundTag modlog$before;

    @Inject(method = "updateText", at = @At("HEAD"))
    private void modlog$before(UnaryOperator<SignText> updater, boolean front, CallbackInfoReturnable<Boolean> cir) {
        modlog$before = SignListener.snapshot((SignBlockEntity) (Object) this);
    }

    @Inject(method = "updateText", at = @At("RETURN"))
    private void modlog$after(UnaryOperator<SignText> updater, boolean front, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && modlog$before != null) {
            SignListener.edited((SignBlockEntity) (Object) this, modlog$before);
        }
        modlog$before = null;
    }
}
