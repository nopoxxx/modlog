package dev.nopox.modlog.inspect;

import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.db.LookupQuery;
import dev.nopox.modlog.lookup.LookupPresenter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inspector mode: left click shows block changes at the clicked block; right click shows item transactions of a
 * block with a block entity (chests, machines, AE2/RS terminals), or block changes at the position the player
 * would place into.
 */
public final class Inspector {
    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();
    private static final Set<ActionType> BLOCK_ACTIONS = EnumSet.of(ActionType.BLOCK_BREAK, ActionType.BLOCK_PLACE);
    private static final Set<ActionType> CONTAINER_ACTIONS = EnumSet.of(ActionType.CONTAINER_ADD, ActionType.CONTAINER_REMOVE);

    private Inspector() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(Inspector::onLeftClick);
        bus.addListener(Inspector::onRightClick);
        bus.addListener(Inspector::onLogout);
    }

    /** @return the new state */
    public static boolean toggle(ServerPlayer player) {
        if (ENABLED.remove(player.getUUID())) {
            return false;
        }
        ENABLED.add(player.getUUID());
        return true;
    }

    private static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START || !isInspecting(event)) {
            return;
        }
        event.setCanceled(true);
        inspect((ServerPlayer) event.getEntity(), event.getPos(), BLOCK_ACTIONS);
    }

    private static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !isInspecting(event)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        BlockPos pos = event.getPos();
        if (event.getLevel().getBlockEntity(pos) != null) {
            inspect((ServerPlayer) event.getEntity(), pos, CONTAINER_ACTIONS);
            return;
        }
        @Nullable Direction face = event.getFace();
        inspect((ServerPlayer) event.getEntity(), face == null ? pos : pos.relative(face), BLOCK_ACTIONS);
    }

    private static boolean isInspecting(PlayerInteractEvent event) {
        return !event.getLevel().isClientSide()
                && event.getEntity() instanceof ServerPlayer player
                && ENABLED.contains(player.getUUID());
    }

    private static void inspect(ServerPlayer player, BlockPos pos, Set<ActionType> actions) {
        ServerLevel level = player.serverLevel();
        LookupQuery query = LookupQuery.at(level.dimension().location().toString(), pos.immutable(), actions);
        LookupPresenter.show(player.createCommandSourceStack(), query, true, 1);
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ENABLED.remove(event.getEntity().getUUID());
        LookupPresenter.forget(event.getEntity().getStringUUID());
    }
}
