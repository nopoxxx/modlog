package dev.nopox.modlog.listener;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.action.Actor;
import dev.nopox.modlog.action.LoggedAction;
import dev.nopox.modlog.container.ContainerContents;
import dev.nopox.modlog.util.NbtBytes;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Logs block breaks, placements and explosions.
 * <p>
 * Events only say an action is <i>about</i> to happen (or happened but may still be reverted), and on Arclight
 * Bukkit plugins can cancel it after we see it. So every event is recorded as pending and only logged at the end
 * of the tick if the world actually changed.
 */
public final class BlockListener {
    private record Pending(ServerLevel level, BlockPos pos, ActionType type, BlockState state, Actor actor, long time,
                           byte @Nullable [] nbt, ContainerContents.@Nullable Summary contents) {
        static Pending breaking(ServerLevel level, BlockPos pos, BlockState state, Actor actor, long time) {
            // Block entity data and contents must be captured now, they're gone after the break
            return new Pending(level, pos, ActionType.BLOCK_BREAK, state, actor, time,
                    NbtBytes.blockEntity(level, pos), ContainerContents.summarize(level, pos));
        }

        static Pending placing(ServerLevel level, BlockPos pos, BlockState state, Actor actor, long time) {
            return new Pending(level, pos, ActionType.BLOCK_PLACE, state, actor, time, null, null);
        }
    }

    private record Key(ServerLevel level, BlockPos pos, ActionType type) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();

    private BlockListener() {
    }

    public static void register(IEventBus bus) {
        // LOWEST so that cancellations by other mods are already applied
        bus.addListener(EventPriority.LOWEST, BlockListener::onBreak);
        bus.addListener(EventPriority.LOWEST, BlockListener::onPlace);
        bus.addListener(EventPriority.LOWEST, BlockListener::onExplosion);
        bus.addListener(BlockListener::onTickEnd);
    }

    /**
     * A block is about to be broken by something that fires no break event (machines). Logged at the end of the tick
     * if it really went.
     */
    public static void breaking(ServerLevel level, BlockPos pos, BlockState state, Actor actor) {
        if (ModLogConfig.LOG_BLOCK_BREAK.get() && !state.isAir()) {
            PENDING.add(Pending.breaking(level, pos, state, actor, System.currentTimeMillis()));
        }
    }

    private static void onBreak(BlockEvent.BreakEvent event) {
        if (!ModLogConfig.LOG_BLOCK_BREAK.get() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState state = event.getState();
        if (state.isAir()) {
            return;
        }
        PENDING.add(Pending.breaking(level, event.getPos().immutable(), state, Actor.of(event.getPlayer()),
                System.currentTimeMillis()));
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!ModLogConfig.LOG_BLOCK_PLACE.get() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Actor actor = Actor.of(event.getEntity());
        long now = System.currentTimeMillis();
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            // Beds, doors, ...: one entry per block
            for (BlockSnapshot snapshot : multi.getReplacedBlockSnapshots()) {
                BlockPos pos = snapshot.getPos().immutable();
                PENDING.add(Pending.placing(level, pos, level.getBlockState(pos), actor, now));
            }
        } else {
            PENDING.add(Pending.placing(level, event.getPos().immutable(), event.getPlacedBlock(), actor, now));
        }
    }

    private static void onExplosion(ExplosionEvent.Detonate event) {
        if (!ModLogConfig.LOG_EXPLOSIONS.get() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Actor actor = Actor.of(event.getExplosion());
        long now = System.currentTimeMillis();
        for (BlockPos pos : event.getAffectedBlocks()) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                PENDING.add(Pending.breaking(level, pos.immutable(), state, actor, now));
            }
        }
    }

    private static void onTickEnd(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        // Chained explosions can report the same block several times in one tick
        Set<Key> seen = new HashSet<>();
        for (Pending p : PENDING) {
            if (!seen.add(new Key(p.level, p.pos, p.type)) || !p.level.isLoaded(p.pos)) {
                continue;
            }
            BlockState current = p.level.getBlockState(p.pos);
            switch (p.type) {
                case BLOCK_BREAK -> {
                    if (!current.is(p.state.getBlock())) {
                        ModLog.log(toAction(p, p.state));
                    }
                }
                case BLOCK_PLACE -> {
                    // Log the current state: neighbours may have reshaped it (stairs, fences, redstone)
                    if (current.is(p.state.getBlock())) {
                        ModLog.log(toAction(p, current));
                        if (current.getBlock() instanceof TntBlock && !p.actor.key().startsWith("#")) {
                            TntTracker.placed(p.level, p.pos, p.actor);
                        }
                    }
                }
                default -> throw new IllegalStateException("Unexpected pending type " + p.type);
            }
        }
        PENDING.clear();
    }

    private static LoggedAction toAction(Pending p, BlockState state) {
        String material = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String serialized = BlockStateParser.serialize(state);
        int props = serialized.indexOf('[');
        String data = props < 0 ? null : serialized.substring(props);
        return new LoggedAction(p.time, p.actor, p.level.dimension().location().toString(),
                p.pos.getX(), p.pos.getY(), p.pos.getZ(), p.type, material, data, 1, p.nbt,
                p.contents == null ? null : p.contents.text(), p.contents == null ? 0 : p.contents.total());
    }
}
