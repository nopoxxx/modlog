package dev.nopox.modlog.listener;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.action.Actor;
import dev.nopox.modlog.action.LoggedAction;
import dev.nopox.modlog.util.Drops;
import dev.nopox.modlog.util.NbtBytes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs entities killed or destroyed, with their data (to bring them back on rollback) and their drops (to take those
 * back). Mobs: any killed by a player, plus named, tamed and villager deaths by anything. Armor stands, item frames,
 * paintings, boats and minecarts: destroyed by a player.
 * <p>
 * Like block breaks, everything is confirmed at the end of the tick. Main thread only.
 */
public final class EntityListener {
    /** An entity that died or may have been destroyed this tick. */
    private record Pending(ServerLevel level, Entity entity, Actor actor, long time, CompoundTag data,
                           List<Drops.Drop> drops, boolean died) {
    }

    private static final Map<Entity, Pending> PENDING = new IdentityHashMap<>();
    /** Item entities near a destroyed (not dying) entity this tick count as its drops. */
    private static final double DROP_RADIUS = 1.5;

    private EntityListener() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, EntityListener::onDeath);
        bus.addListener(EventPriority.LOWEST, EntityListener::onDrops);
        bus.addListener(EventPriority.LOWEST, EntityListener::onAttack);
        bus.addListener(EntityListener::onItemSpawn);
        bus.addListener(EntityListener::onTickEnd);
    }

    private static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!ModLogConfig.LOG_ENTITY_KILLS.get() || !(entity.level() instanceof ServerLevel level) || entity instanceof Player) {
            return;
        }
        Actor killer = killerOf(event.getSource());
        boolean byPlayer = !killer.key().startsWith("#");
        if (byPlayer || valuable(entity)) {
            PENDING.put(entity, new Pending(level, entity, killer, System.currentTimeMillis(), snapshot(entity),
                    new ArrayList<>(), true));
        }
    }

    private static void onDrops(LivingDropsEvent event) {
        Pending pending = PENDING.get(event.getEntity());
        if (pending != null) {
            for (ItemEntity drop : event.getDrops()) {
                pending.drops.add(new Drops.Drop(drop.getItem().copy(), drop.getUUID()));
            }
        }
    }

    /** Entities that don't die but are destroyed by hitting them. */
    private static void onAttack(AttackEntityEvent event) {
        Entity target = event.getTarget();
        if (!ModLogConfig.LOG_ENTITY_KILLS.get() || !(target.level() instanceof ServerLevel level)
                || !(target instanceof ArmorStand || target instanceof HangingEntity || target instanceof VehicleEntity)
                || PENDING.containsKey(target)) {
            return;
        }
        PENDING.put(target, new Pending(level, target, Actor.of(event.getEntity()), System.currentTimeMillis(),
                snapshot(target), new ArrayList<>(), false));
    }

    private static void onItemSpawn(EntityJoinLevelEvent event) {
        if (PENDING.isEmpty() || event.loadedFromDisk() || !(event.getEntity() instanceof ItemEntity item)) {
            return;
        }
        for (Pending pending : PENDING.values()) {
            if (!pending.died && pending.level == event.getLevel()
                    && pending.entity.position().closerThan(item.position(), DROP_RADIUS)) {
                pending.drops.add(new Drops.Drop(item.getItem().copy(), item.getUUID()));
                return;
            }
        }
    }

    private static void onTickEnd(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Pending p : PENDING.values()) {
            // A hit that didn't destroy it isn't logged
            boolean gone = p.died || (p.entity.isRemoved() && p.entity.getRemovalReason() == Entity.RemovalReason.KILLED);
            if (gone) {
                log(p);
            }
        }
        PENDING.clear();
    }

    private static void log(Pending p) {
        BlockPos pos = p.entity.blockPosition();
        ModLog.log(new LoggedAction(p.time, p.actor, p.level.dimension().location().toString(), pos.getX(), pos.getY(),
                pos.getZ(), ActionType.ENTITY_KILL, EntityType.getKey(p.entity.getType()).toString(),
                p.entity.hasCustomName() ? p.entity.getCustomName().getString() : null, 1, NbtBytes.compress(p.data),
                null, 0, Drops.encode(p.drops, p.level.registryAccess())));
    }

    /** Full entity data, as it was alive, so it can be brought back. */
    private static CompoundTag snapshot(Entity entity) {
        CompoundTag data = entity.saveWithoutId(new CompoundTag());
        data.putString("id", EntityType.getKey(entity.getType()).toString());
        return data;
    }

    /** Whoever is responsible: a player (also through projectiles and pets), else the killing entity or damage type. */
    private static Actor killerOf(DamageSource source) {
        Entity killer = source.getEntity();
        if (killer instanceof OwnableEntity pet && pet.getOwner() instanceof Player owner) {
            return Actor.of(owner);
        }
        if (killer != null) {
            return Actor.of(killer);
        }
        return source.getDirectEntity() != null ? Actor.of(source.getDirectEntity()) : Actor.nonPlayer(source.getMsgId());
    }

    /** Deaths worth logging even without a player involved. */
    private static boolean valuable(LivingEntity entity) {
        return entity.hasCustomName()
                || (entity instanceof OwnableEntity pet && pet.getOwnerUUID() != null)
                || entity instanceof AbstractVillager
                || entity instanceof ArmorStand;
    }
}
