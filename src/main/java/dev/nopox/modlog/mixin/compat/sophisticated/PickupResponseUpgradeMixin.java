package dev.nopox.modlog.mixin.compat.sophisticated;

import dev.nopox.modlog.compat.BackpackPickups;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.pickup.PickupUpgradeWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Logs items the pickup and magnet upgrades divert into their storage when the player picks something up. */
@Mixin({PickupUpgradeWrapper.class, MagnetUpgradeWrapper.class})
public abstract class PickupResponseUpgradeMixin {
    @Inject(method = "pickup", at = @At("RETURN"))
    private void modlog$logPickup(Level level, ItemStack stack, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        int inserted = stack.getCount() - cir.getReturnValue().getCount();
        if (!simulate && inserted > 0) {
            BackpackPickups.pickup(((UpgradeWrapperBaseAccessor) this).modlog$getStorageWrapper().getContentsUuid(),
                    level, stack, inserted);
        }
    }
}
