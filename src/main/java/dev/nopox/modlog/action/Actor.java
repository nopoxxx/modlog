package dev.nopox.modlog.action;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Who caused an action.
 *
 * @param key  stable identity: a player's UUID, or a {@code #}-prefixed name for everything else
 * @param name display name, also what {@code u:} matches in lookups
 */
public record Actor(String key, String name) {
    public static final Actor UNKNOWN = nonPlayer("unknown");

    public static Actor of(Player player) {
        if (player instanceof FakePlayer) {
            // Machines (Create deployers, Mekanism miners, ...) acting through a fake player
            return nonPlayer(player.getGameProfile().getName());
        }
        return new Actor(player.getUUID().toString(), player.getGameProfile().getName());
    }

    public static Actor of(@Nullable Entity entity) {
        if (entity == null) {
            return UNKNOWN;
        }
        if (entity instanceof Player player) {
            return of(player);
        }
        return nonPlayer(EntityType.getKey(entity.getType()).getPath());
    }

    /** Prefers whoever is ultimately responsible, e.g. the player who lit the TNT. */
    public static Actor of(Explosion explosion) {
        Entity source = explosion.getIndirectSourceEntity();
        if (source == null) {
            source = explosion.getDirectSourceEntity();
        }
        return source == null ? nonPlayer("explosion") : of(source);
    }

    public static Actor nonPlayer(String name) {
        String tagged = name.startsWith("#") ? name : "#" + name;
        return new Actor(tagged, tagged);
    }
}
