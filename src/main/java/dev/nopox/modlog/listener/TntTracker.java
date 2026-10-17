package dev.nopox.modlog.listener;

import dev.nopox.modlog.action.Actor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Remembers who placed TNT, so an explosion of TNT lit without a player (redstone, a lever, fire) is attributed to
 * the player who placed it instead of to {@code #tnt}. Main thread only.
 */
public final class TntTracker {
    private static final int MAX_TRACKED_BLOCKS = 10_000;

    /** Placed TNT blocks by position, oldest first so the map can be capped. */
    private static final Map<GlobalPos, Actor> PLACED = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<GlobalPos, Actor> eldest) {
            return size() > MAX_TRACKED_BLOCKS;
        }
    };
    private static final Map<Entity, Actor> PRIMED = new WeakHashMap<>();

    private TntTracker() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(TntTracker::onJoin);
        bus.addListener((ServerStoppedEvent e) -> {
            PLACED.clear();
            PRIMED.clear();
        });
    }

    /** A player placed a TNT block. */
    static void placed(Level level, BlockPos pos, Actor actor) {
        PLACED.put(GlobalPos.of(level.dimension(), pos.immutable()), actor);
    }

    /** Who placed the TNT this primed TNT came from, if it was lit without a player. */
    public static @Nullable Actor placerOf(Entity primed) {
        return PRIMED.get(primed);
    }

    private static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk() || !(event.getEntity() instanceof PrimedTnt tnt)) {
            return;
        }
        // Lit TNT turns into a primed entity at the block's position
        Actor placer = PLACED.remove(GlobalPos.of(event.getLevel().dimension(), tnt.blockPosition()));
        if (placer != null && tnt.getOwner() == null) {
            PRIMED.put(tnt, placer);
        }
    }
}
