package dev.nopox.modlog.listener;

import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.action.Actor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logs fluids players place and pick up with buckets (vanilla or modded fluid containers), which fire no block
 * event: the blocks the player is aiming at are compared before and after the use packet. Waterlogging isn't covered.
 */
public final class FluidTracker {
    private FluidTracker() {
    }

    /** Wraps the handling of a use packet if the player holds a fluid container. Main thread. */
    public static Runnable around(ServerPlayer player, Packet<?> packet, Runnable task) {
        if (!ModLogConfig.LOG_FLUIDS.get()) {
            return task;
        }
        InteractionHand hand;
        if (packet instanceof ServerboundUseItemPacket use) {
            hand = use.getHand();
        } else if (packet instanceof ServerboundUseItemOnPacket use) {
            hand = use.getHand();
        } else {
            return task;
        }
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof BucketItem) && held.getCapability(Capabilities.FluidHandler.ITEM) == null) {
            return task;
        }
        return () -> {
            ServerLevel level = player.serverLevel();
            Map<BlockPos, BlockState> before = new LinkedHashMap<>();
            if (packet instanceof ServerboundUseItemOnPacket use) {
                remember(level, use.getHitResult(), before);
            }
            // Buckets trace their own target: through fluids to pick one up, onto blocks to place one
            remember(level, aim(player, ClipContext.Fluid.SOURCE_ONLY), before);
            remember(level, aim(player, ClipContext.Fluid.NONE), before);

            task.run();

            Actor actor = Actor.of(player);
            before.forEach((pos, old) -> {
                BlockState now = level.getBlockState(pos);
                boolean wasFluid = old.getBlock() instanceof LiquidBlock;
                boolean isFluid = now.getBlock() instanceof LiquidBlock;
                if (!wasFluid && isFluid) {
                    BlockListener.logNow(level, pos, ActionType.BLOCK_PLACE, now, actor);
                } else if (wasFluid && old.getFluidState().isSource() && !isFluid && now.isAir()) {
                    BlockListener.logNow(level, pos, ActionType.BLOCK_BREAK, old, actor);
                }
            });
        };
    }

    private static void remember(ServerLevel level, HitResult hit, Map<BlockPos, BlockState> states) {
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            for (BlockPos pos : new BlockPos[]{block.getBlockPos(), block.getBlockPos().relative(block.getDirection())}) {
                states.putIfAbsent(pos.immutable(), level.getBlockState(pos));
            }
        }
    }

    private static HitResult aim(ServerPlayer player, ClipContext.Fluid fluid) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        return player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, player));
    }
}
