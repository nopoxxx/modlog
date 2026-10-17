package dev.nopox.modlog.mixin.compat.sophisticated;

import dev.nopox.modlog.compat.BackpackPickups;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Logs items the magnet upgrade pulls from the ground into its storage. */
@Mixin(MagnetUpgradeWrapper.class)
public abstract class MagnetUpgradeWrapperMixin {
    @Unique
    private Level modlog$level;
    @Unique
    private BlockPos modlog$pos;
    @Unique
    private ItemStack modlog$item = ItemStack.EMPTY;
    @Unique
    private int modlog$countBefore;

    @Inject(method = "pickupItems", at = @At("HEAD"))
    private void modlog$rememberWhere(Entity entity, Level level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        modlog$level = level;
        modlog$pos = pos;
    }

    @Inject(method = "tryToInsertItem", at = @At("HEAD"))
    private void modlog$before(Player player, ItemEntity itemEntity, CallbackInfoReturnable<Boolean> cir) {
        modlog$item = itemEntity.getItem().copy();
        modlog$countBefore = itemEntity.getItem().getCount();
    }

    @Inject(method = "tryToInsertItem", at = @At("RETURN"))
    private void modlog$after(Player player, ItemEntity itemEntity, CallbackInfoReturnable<Boolean> cir) {
        int inserted = modlog$countBefore - itemEntity.getItem().getCount();
        if (inserted > 0 && modlog$level != null) {
            BackpackPickups.magnet(((UpgradeWrapperBaseAccessor) this).modlog$getStorageWrapper().getContentsUuid(),
                    player, modlog$level, modlog$pos, modlog$item, inserted);
        }
        modlog$item = ItemStack.EMPTY;
    }
}
