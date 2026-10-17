package dev.nopox.modlog.rollback;

import com.mojang.authlib.GameProfile;
import dev.nopox.modlog.container.ItemKey;
import dev.nopox.modlog.util.Ids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Takes back items that a rolled-back action gave to someone, so rollback doesn't duplicate them: what a player took
 * out of a container, what fell out of a broken block.
 * <p>
 * One instance per rollback job. Items the job itself already removed from the world (undoing someone putting them
 * into a container or picking them up) are kept as credit and used first: a thief who moved stolen items into another
 * chest has them taken from that chest, not a second time from their inventory.
 */
final class Reclaimer {
    /** Items taken during the job, to give back if the rollback is undone. @param player null: from the ground */
    record Taken(@Nullable UUID player, ItemStack stack) {
    }

    /** Where to look first. */
    enum Order {
        /** What fell out of a broken block: it's most likely still on the ground. */
        GROUND_FIRST,
        /** What a player took out of a container: most likely in their inventory or moved on. */
        TAKER_FIRST
    }

    private static final double GROUND_RADIUS = 3;

    /** Items taken back for a container that had no room for them at that moment. */
    private record Overflow(ServerLevel level, BlockPos pos, @Nullable String data, ItemStack stack) {
    }

    private final List<Overflow> overflow = new ArrayList<>();
    int overflowInserted;
    int overflowDropped;
    int overflowDiscarded;

    private final MinecraftServer server;
    private final Map<ItemKey, Integer> credit = new HashMap<>();
    /**
     * Blocks the job removed that a player had placed: the item they were placed from is back out of the world. Matched
     * by item only, since the item's data (a machine's contents) moved into the block when it was placed.
     */
    private final Map<Item, Integer> placedCredit = new HashMap<>();
    private final Map<Long, List<Taken>> takenByAction = new HashMap<>();
    /** "item|who|reason" -> count of what couldn't be found */
    private final Map<String, Integer> missing = new TreeMap<>();
    private long currentAction;
    int fromPlayers;
    int fromGround;
    int fromCredit;

    Reclaimer(MinecraftServer server) {
        this.server = server;
    }

    void beginAction(long actionId) {
        currentAction = actionId;
    }

    /** The job removed these items from the world (undid a deposit or pickup). */
    void credit(ItemStack template, int count) {
        if (count > 0) {
            credit.merge(new ItemKey(template), count, Integer::sum);
        }
    }

    /** The job removed a block a player had placed from this item. */
    void creditPlaced(Item item) {
        if (item != Items.AIR) {
            placedCredit.merge(item, 1, Integer::sum);
        }
    }

    /**
     * Takes back {@code count} of {@code template}.
     *
     * @param takerKey  actor key of who got the items (a UUID for players)
     * @param entity    the item entity they fell out as, if known
     */
    void reclaim(ItemStack template, int count, String takerKey, ServerLevel level, BlockPos pos,
                 @Nullable UUID entity, Order order) {
        ItemKey key = new ItemKey(template);
        int left = count;
        if (entity != null) {
            left -= fromEntity(level.getEntity(entity), key, left);
        }
        if (order == Order.GROUND_FIRST) {
            left -= fromGround(level, pos, key, left);
            left -= fromCredit(key, left);
            left -= fromTaker(takerKey, key, left);
        } else {
            left -= fromCredit(key, left);
            left -= fromTaker(takerKey, key, left);
            left -= fromGround(level, pos, key, left);
        }
        if (left > 0) {
            missing.merge(String.join("|", BuiltInRegistries.ITEM.getKey(template.getItem()).toString(),
                    nameOf(takerKey), reasonFor(takerKey)), left, Integer::sum);
        }
    }

    /** Items that belong back in a container that can't take them right now; settled when the job ends. */
    void overflow(ServerLevel level, BlockPos pos, @Nullable String data, ItemStack template, int count) {
        overflow.add(new Overflow(level, pos.immutable(), data, template.copyWithCount(count)));
    }

    /**
     * Once all actions are applied: put overflow into its container if there's room now, drop the rest next to it. If
     * the rollback removed the container itself (e.g. a machine placed after it was picked up), the items were only
     * a passing copy of what the restored original holds, so they're discarded.
     */
    void settleOverflow() {
        for (Overflow o : overflow) {
            int count = o.stack.getCount();
            int inserted = ActionApplier.insertInto(o.level, o.pos, o.data, o.stack, count);
            if (inserted < 0) {
                overflowDiscarded += count;
                continue;
            }
            overflowInserted += inserted;
            if (inserted < count) {
                overflowDropped += count - inserted;
                Containers.dropItemStack(o.level, o.pos.getX() + 0.5, o.pos.getY() + 1, o.pos.getZ() + 0.5,
                        o.stack.copyWithCount(count - inserted));
            }
        }
        overflow.clear();
    }

    private int fromEntity(@Nullable Entity entity, ItemKey key, int wanted) {
        if (wanted <= 0 || !(entity instanceof ItemEntity item) || !item.isAlive() || !key.equals(new ItemKey(item.getItem()))) {
            return 0;
        }
        int take = Math.min(wanted, item.getItem().getCount());
        recordTaken(null, key, take);
        fromGround += take;
        shrink(item, take);
        return take;
    }

    private int fromGround(ServerLevel level, BlockPos pos, ItemKey key, int wanted) {
        int taken = 0;
        if (wanted > 0) {
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(GROUND_RADIUS))) {
                if (taken >= wanted) {
                    break;
                }
                taken += fromEntity(item, key, wanted - taken);
            }
        }
        return taken;
    }

    private int fromCredit(ItemKey key, int wanted) {
        int taken = take(credit, key, wanted);
        taken += take(placedCredit, key.stack().getItem(), wanted - taken);
        fromCredit += taken;
        return taken;
    }

    private static <K> int take(Map<K, Integer> credits, K key, int wanted) {
        int available = credits.getOrDefault(key, 0);
        int take = Math.min(wanted, available);
        if (take <= 0) {
            return 0;
        }
        credits.put(key, available - take);
        return take;
    }

    private int fromTaker(String takerKey, ItemKey key, int wanted) {
        ServerPlayer player = onlinePlayer(takerKey);
        if (wanted <= 0 || player == null) {
            return 0;
        }
        Inventory inventory = player.getInventory();
        int taken = 0;
        for (int i = 0; i < inventory.getContainerSize() && taken < wanted; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && key.equals(new ItemKey(stack))) {
                int take = Math.min(wanted - taken, stack.getCount());
                stack.shrink(take);
                taken += take;
            }
        }
        if (taken > 0) {
            inventory.setChanged();
            recordTaken(player.getUUID(), key, taken);
            fromPlayers += taken;
        }
        return taken;
    }

    private static void shrink(ItemEntity item, int count) {
        ItemStack rest = item.getItem().copy();
        rest.shrink(count);
        if (rest.isEmpty()) {
            item.discard();
        } else {
            item.setItem(rest);
        }
    }

    private void recordTaken(@Nullable UUID player, ItemKey key, int count) {
        takenByAction.computeIfAbsent(currentAction, id -> new ArrayList<>()).add(new Taken(player, key.stack().copyWithCount(count)));
    }

    /** What was taken while applying each action, for undo. */
    Map<Long, List<Taken>> takenByAction() {
        return takenByAction;
    }

    int reclaimed() {
        return fromPlayers + fromGround + fromCredit;
    }

    /** Lines like {@code 2 x mekanism:metallurgic_infuser from nopox_ (offline)}. */
    List<String> missingSummary() {
        List<String> lines = new ArrayList<>();
        missing.forEach((key, count) -> {
            String[] parts = key.split("\\|", -1);
            lines.add("%d x %s from %s (%s)".formatted(count, Ids.shortId(parts[0]), parts[1], parts[2]));
        });
        return lines;
    }

    private @Nullable ServerPlayer onlinePlayer(String actorKey) {
        UUID uuid = uuidOf(actorKey);
        return uuid == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    private String nameOf(String actorKey) {
        UUID uuid = uuidOf(actorKey);
        if (uuid == null) {
            return actorKey;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            return player.getGameProfile().getName();
        }
        return server.getProfileCache() == null ? actorKey
                : server.getProfileCache().get(uuid).map(GameProfile::getName).orElse(actorKey);
    }

    private String reasonFor(String actorKey) {
        if (uuidOf(actorKey) == null) {
            return "not a player";
        }
        return onlinePlayer(actorKey) == null ? "offline" : "used up or given away";
    }

    private static @Nullable UUID uuidOf(String actorKey) {
        if (actorKey.startsWith("#")) {
            return null;
        }
        try {
            return UUID.fromString(actorKey);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
