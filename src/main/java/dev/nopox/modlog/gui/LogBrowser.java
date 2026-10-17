package dev.nopox.modlog.gui;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.compat.ItemStorages;
import dev.nopox.modlog.compat.NetworkStorages;
import dev.nopox.modlog.db.LookupQuery;
import dev.nopox.modlog.db.LookupRow;
import dev.nopox.modlog.db.Storage;
import dev.nopox.modlog.rollback.RollbackManager;
import dev.nopox.modlog.util.Ids;
import dev.nopox.modlog.util.TimeFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * In-game log browser: a vanilla 9x6 chest GUI whose items are entries and buttons, so clients need no mod.
 * Nothing in it can be taken; every click is turned into an action and the client's view is resynced.
 */
public final class LogBrowser {
    private static final int ROWS = 6;
    private static final int FIRST_ENTRY = 9;
    private static final int PAGE_SIZE = 36;
    private static final long CONFIRM_MS = 5000;

    // Top row: filters
    private static final int SLOT_ACTIONS = 0;
    private static final int SLOT_TIME = 1;
    private static final int SLOT_AREA = 2;
    private static final int SLOT_PLAYER = 3;
    private static final int SLOT_MATERIAL = 4;
    private static final int SLOT_ROLLED_BACK = 5;
    private static final int SLOT_REFRESH = 7;
    private static final int SLOT_CLOSE = 8;
    // Bottom row: paging and rollback
    private static final int SLOT_PREV = 45;
    private static final int SLOT_ROLLBACK = 47;
    private static final int SLOT_RESTORE = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_UNDO = 50;
    private static final int SLOT_NEXT = 53;

    private enum ActionFilter {
        ALL(Items.COMPASS, "All actions", EnumSet.noneOf(ActionType.class)),
        BLOCKS(Items.GRASS_BLOCK, "Blocks", EnumSet.of(ActionType.BLOCK_BREAK, ActionType.BLOCK_PLACE)),
        CONTAINERS(Items.CHEST, "Containers", EnumSet.of(ActionType.CONTAINER_ADD, ActionType.CONTAINER_REMOVE, ActionType.ITEM_PICKUP)),
        ITEMS(Items.HOPPER, "Item drops/pickups", EnumSet.of(ActionType.ITEM_DROP, ActionType.ITEM_PICKUP));

        final Item icon;
        final String label;
        final Set<ActionType> actions;

        ActionFilter(Item icon, String label, Set<ActionType> actions) {
            this.icon = icon;
            this.label = label;
            this.actions = actions;
        }
    }

    private static final String[] TIME_LABELS = {"1 hour", "6 hours", "24 hours", "7 days", "30 days", "all time"};
    private static final long[] TIME_MS = {3_600_000L, 21_600_000L, 86_400_000L, 604_800_000L, 2_592_000_000L, -1};
    private static final int TIME_ALL = TIME_MS.length - 1;

    private static final String[] AREA_LABELS = {"10 blocks", "30 blocks", "100 blocks", "this world", "everywhere"};
    private static final int[] AREA_RADIUS = {10, 30, 100, -1, -1};
    private static final int AREA_GLOBAL = 4;
    private static final int MAX_TYPED_RADIUS = 10_000;

    private static final Component TITLE = Component.literal("ModLog");

    private record Confirm(int slot, long expiresAt) {
    }

    private final ServerPlayer player;
    private final SimpleContainer container = new SimpleContainer(ROWS * 9);
    private final String world;
    private final BlockPos center;
    private @Nullable Menu menu;
    private boolean closed;

    private int actionFilter;
    private int timeIndex = 2;
    private int areaIndex = 1;
    /** Typed by the player via a sign; overrides {@link #timeIndex} when set. */
    private @Nullable Long customTimeMs;
    /** Typed by the player via a sign; overrides {@link #areaIndex} when positive. */
    private int customRadius;
    private boolean hideRolledBack;
    private final Set<String> actors;
    private final Set<String> materials;
    private int page = 1;
    private List<LookupRow> rows = List.of();
    private int total;
    private boolean loading;
    private int generation;
    private @Nullable Confirm confirm;

    private LogBrowser(ServerPlayer player, Set<String> actors, Set<String> materials) {
        this.player = player;
        this.world = player.serverLevel().dimension().location().toString();
        this.center = player.blockPosition();
        this.actors = new HashSet<>(actors);
        this.materials = new HashSet<>(materials);
    }

    /** Opens the browser centred on the player's position, optionally pre-filtered. */
    public static void open(ServerPlayer player, Set<String> actors, Set<String> materials) {
        new LogBrowser(player, actors, materials).show();
    }

    private void show() {
        closed = false;
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new Menu(id, inventory, this), TITLE));
        reload();
    }

    // ---- Data ----

    private LookupQuery query() {
        int radius = customRadius > 0 ? customRadius : AREA_RADIUS[areaIndex];
        @Nullable String queryWorld = customRadius == 0 && areaIndex == AREA_GLOBAL ? null : world;
        @Nullable BlockPos queryCenter = radius > 0 ? center : null;
        @Nullable Long window = timeWindow();
        @Nullable Long since = window == null ? null : System.currentTimeMillis() - window;
        return new LookupQuery(Set.copyOf(actors), ActionFilter.values()[actionFilter].actions, Set.copyOf(materials),
                queryWorld, queryCenter, Math.max(0, radius), since, Set.of(), hideRolledBack);
    }

    /** @return millis, or null for all time */
    private @Nullable Long timeWindow() {
        if (customTimeMs != null) {
            return customTimeMs;
        }
        return timeIndex == TIME_ALL ? null : TIME_MS[timeIndex];
    }

    private void reload() {
        Storage storage = ModLog.storage();
        if (storage == null || closed) {
            return;
        }
        int gen = ++generation;
        loading = true;
        render();
        storage.lookup(query(), (page - 1) * PAGE_SIZE, PAGE_SIZE).whenComplete((result, error) ->
                player.server.execute(() -> {
                    // Ignore results of a query the player has since replaced
                    if (gen != generation || closed) {
                        return;
                    }
                    loading = false;
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        ModLog.LOGGER.error("GUI lookup failed", cause);
                        player.sendSystemMessage(Component.literal("Lookup failed: " + cause.getMessage()).withStyle(ChatFormatting.RED));
                    } else {
                        rows = result.rows();
                        total = result.total();
                        if (rows.isEmpty() && page > 1) {
                            page = 1;
                            reload();
                            return;
                        }
                    }
                    render();
                }));
    }

    /** Refreshes after a rollback if the GUI is still open. */
    private void reloadIfOpen() {
        if (!closed) {
            reload();
        }
    }

    // ---- Clicks ----

    private void onClick(int slot, int button, ClickType type) {
        boolean right = button == 1;
        if (confirm != null && (confirm.slot != slot || System.currentTimeMillis() > confirm.expiresAt)) {
            confirm = null;
        }
        boolean typed = type == ClickType.QUICK_MOVE && right;
        switch (slot) {
            case SLOT_ACTIONS -> cycle(() -> actionFilter = Math.floorMod(actionFilter + (right ? -1 : 1), ActionFilter.values().length));
            case SLOT_TIME -> {
                if (typed) {
                    askInput(slot);
                } else {
                    cycle(() -> {
                        // Leaving a typed value continues from the last preset
                        if (customTimeMs == null) {
                            timeIndex = Math.floorMod(timeIndex + (right ? -1 : 1), TIME_MS.length);
                        }
                        customTimeMs = null;
                    });
                }
            }
            case SLOT_AREA -> {
                if (typed) {
                    askInput(slot);
                } else {
                    cycle(() -> {
                        if (customRadius == 0) {
                            areaIndex = Math.floorMod(areaIndex + (right ? -1 : 1), AREA_RADIUS.length);
                        }
                        customRadius = 0;
                    });
                }
            }
            case SLOT_PLAYER -> {
                if (typed) {
                    askInput(slot);
                } else {
                    cycle(actors::clear);
                }
            }
            case SLOT_MATERIAL -> {
                if (typed) {
                    askInput(slot);
                } else {
                    cycle(materials::clear);
                }
            }
            case SLOT_ROLLED_BACK -> cycle(() -> hideRolledBack = !hideRolledBack);
            case SLOT_REFRESH -> reload();
            case SLOT_CLOSE -> player.closeContainer();
            case SLOT_PREV -> {
                if (page > 1) {
                    page--;
                    reload();
                }
            }
            case SLOT_NEXT -> {
                if (page < pages()) {
                    page++;
                    reload();
                }
            }
            case SLOT_ROLLBACK, SLOT_RESTORE -> bulk(slot, slot == SLOT_ROLLBACK);
            case SLOT_UNDO -> {
                if (requireAdmin()) {
                    RollbackManager.undo(player.createCommandSourceStack(), this::reloadIfOpen);
                }
            }
            default -> {
                int index = slot - FIRST_ENTRY;
                if (index >= 0 && index < rows.size()) {
                    onEntryClick(rows.get(index), right, type == ClickType.QUICK_MOVE);
                }
            }
        }
    }

    private void cycle(Runnable change) {
        change.run();
        page = 1;
        reload();
    }

    /** Lets the player type a filter value in an anvil, then reopens the GUI with it. */
    private void askInput(int slot) {
        String prompt;
        String initial;
        Consumer<String> apply;
        switch (slot) {
            case SLOT_TIME -> {
                prompt = "Time, e.g. 10m, 2h30m, 1d";
                initial = customTimeMs != null ? TimeFormat.duration(customTimeMs) : "";
                apply = text -> customTimeMs = TimeFormat.parseDuration(text);
            }
            case SLOT_AREA -> {
                prompt = "Radius in blocks, e.g. 25";
                initial = customRadius > 0 ? Integer.toString(customRadius) : "";
                apply = text -> {
                    int radius = Integer.parseInt(text);
                    if (radius <= 0 || radius > MAX_TYPED_RADIUS) {
                        throw new IllegalArgumentException("radius must be 1.." + MAX_TYPED_RADIUS);
                    }
                    customRadius = radius;
                };
            }
            case SLOT_PLAYER -> {
                prompt = "Player name, or #tnt, #creeper";
                initial = actors.size() == 1 ? actors.iterator().next() : "";
                apply = text -> {
                    actors.clear();
                    actors.add(text);
                };
            }
            case SLOT_MATERIAL -> {
                prompt = "Block/item id, e.g. chest, ae2:...";
                initial = materials.size() == 1 ? Ids.shortId(materials.iterator().next()) : "";
                apply = text -> {
                    String id = text.toLowerCase(Locale.ROOT);
                    materials.clear();
                    materials.add(id.contains(":") ? id : "minecraft:" + id);
                };
            }
            default -> {
                return;
            }
        }
        AnvilInput.open(player, prompt, initial, text -> {
            if (text != null && !text.isEmpty()) {
                try {
                    apply.accept(text);
                    page = 1;
                } catch (IllegalArgumentException e) {
                    player.sendSystemMessage(Component.literal("Invalid value '" + text + "': " + e.getMessage())
                            .withStyle(ChatFormatting.RED));
                }
            }
            show();
        });
    }

    private void onEntryClick(LookupRow row, boolean right, boolean shift) {
        if (shift && !right) {
            // Toggle just this entry
            if (requireAdmin()) {
                RollbackManager.start(player.createCommandSourceStack(), LookupQuery.ofId(row.id()), !row.rolledBack(),
                        this::reloadIfOpen);
            }
        } else if (shift) {
            materials.clear();
            materials.add(row.material());
            cycle(() -> {
            });
        } else if (right) {
            actors.clear();
            actors.add(row.actor());
            cycle(() -> {
            });
        } else {
            teleport(row);
        }
    }

    private void bulk(int slot, boolean rollback) {
        if (!requireAdmin()) {
            return;
        }
        if (timeWindow() == null) {
            player.sendSystemMessage(Component.literal("Pick a time range first, rolling back all time isn't allowed from here")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (confirm == null) {
            // First click arms the button, the second one within a few seconds runs it
            confirm = new Confirm(slot, System.currentTimeMillis() + CONFIRM_MS);
            render();
            return;
        }
        confirm = null;
        RollbackManager.start(player.createCommandSourceStack(), query(), rollback, this::reloadIfOpen);
        render();
    }

    private boolean requireAdmin() {
        if (player.hasPermissions(Commands.LEVEL_ADMINS)) {
            return true;
        }
        player.sendSystemMessage(Component.literal("You need permission level 3 to roll back").withStyle(ChatFormatting.RED));
        return false;
    }

    private void teleport(LookupRow row) {
        ServerLevel level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(row.world())));
        if (level == null) {
            player.sendSystemMessage(Component.literal("World " + row.world() + " isn't loaded").withStyle(ChatFormatting.RED));
            return;
        }
        player.closeContainer();
        player.teleportTo(level, row.x() + 0.5, row.y() + 1, row.z() + 0.5, player.getYRot(), player.getXRot());
    }

    private int pages() {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    // ---- Rendering ----

    private void render() {
        container.clearContent();
        ActionFilter filter = ActionFilter.values()[actionFilter];
        container.setItem(SLOT_ACTIONS, button(filter.icon, "Show: " + filter.label,
                options(ActionFilter.values().length, actionFilter, i -> ActionFilter.values()[i].label)));
        container.setItem(SLOT_TIME, button(Items.CLOCK, customTimeMs != null
                        ? "Time: last " + TimeFormat.duration(customTimeMs) + " (typed)"
                        : "Time: last " + TIME_LABELS[timeIndex],
                withHint(options(TIME_LABELS.length, customTimeMs != null ? -1 : timeIndex, i -> TIME_LABELS[i]),
                        "Shift+right-click: type your own, e.g. 10m")));
        container.setItem(SLOT_AREA, button(Items.SPYGLASS, customRadius > 0
                        ? "Area: %d blocks (typed)".formatted(customRadius)
                        : "Area: " + AREA_LABELS[areaIndex],
                withHint(withHint(options(AREA_LABELS.length, customRadius > 0 ? -1 : areaIndex, i -> AREA_LABELS[i]),
                                "Shift+right-click: type a radius"),
                        "Around %d %d %d (where you opened this)".formatted(center.getX(), center.getY(), center.getZ()))));
        container.setItem(SLOT_PLAYER, button(Items.PLAYER_HEAD, "Player filter: " + (actors.isEmpty() ? "all players" : String.join(", ", actors)),
                filterLore(actors.isEmpty(), "Shows only actions done by this player",
                        "Right-click an entry: use its player", "Shift+right-click: type a name")));
        container.setItem(SLOT_MATERIAL, button(Items.ITEM_FRAME, "Block/item filter: " + (materials.isEmpty() ? "all"
                        : String.join(", ", materials.stream().map(Ids::shortId).toList())),
                filterLore(materials.isEmpty(), "Shows only actions with this block/item",
                        "Shift+right-click an entry: use its block/item", "Shift+right-click: type an id")));
        container.setItem(SLOT_ROLLED_BACK, button(hideRolledBack ? Items.GRAY_DYE : Items.LIME_DYE,
                "Rolled back actions: " + (hideRolledBack ? "hidden" : "shown"), List.of(hint("Click to toggle"))));
        container.setItem(SLOT_REFRESH, button(Items.SUNFLOWER, "Refresh", List.of()));
        container.setItem(SLOT_CLOSE, button(Items.BARRIER, "Close", List.of()));

        long now = System.currentTimeMillis();
        for (int i = 0; i < rows.size() && i < PAGE_SIZE; i++) {
            container.setItem(FIRST_ENTRY + i, entry(rows.get(i), now));
        }

        if (page > 1) {
            container.setItem(SLOT_PREV, button(Items.ARROW, "Previous page", List.of()));
        }
        if (page < pages()) {
            container.setItem(SLOT_NEXT, button(Items.ARROW, "Next page", List.of()));
        }
        container.setItem(SLOT_INFO, button(Items.PAPER, loading ? "Loading..." : "Page %d/%d".formatted(page, pages()),
                List.of(line(total + " actions match", ChatFormatting.GRAY))));
        container.setItem(SLOT_ROLLBACK, bulkButton(SLOT_ROLLBACK, Items.TNT, "Roll back all %d shown actions"));
        container.setItem(SLOT_RESTORE, bulkButton(SLOT_RESTORE, Items.EMERALD, "Restore all %d shown actions"));
        container.setItem(SLOT_UNDO, button(Items.RECOVERY_COMPASS, "Undo my last rollback/restore", List.of()));

        if (menu != null) {
            menu.broadcastChanges();
        }
    }

    private ItemStack bulkButton(int slot, Item icon, String text) {
        boolean armed = confirm != null && confirm.slot == slot;
        String title = armed ? "Click again to confirm" : text.formatted(total);
        List<Component> lore = new ArrayList<>();
        lore.add(line("Uses the filters above", ChatFormatting.GRAY));
        if (timeWindow() == null) {
            lore.add(line("Pick a time range first", ChatFormatting.RED));
        }
        ItemStack stack = button(icon, title, lore);
        if (armed) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    private ItemStack entry(LookupRow row, long now) {
        ItemStack stack = new ItemStack(iconOf(row), Math.max(1, Math.min(row.amount(), 64)));
        MutableComponent name = Component.literal(row.actor()).withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(" " + row.type().verb + " ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal((row.amount() > 1 ? row.amount() + " x " : "") + Ids.shortId(row.material()))
                        .withStyle(ChatFormatting.GOLD));
        if (row.rolledBack()) {
            name.withStyle(ChatFormatting.STRIKETHROUGH);
        }

        List<Component> lore = new ArrayList<>();
        lore.add(line(TimeFormat.ago(now - row.time()), ChatFormatting.GRAY));
        lore.add(line("%d %d %d  %s".formatted(row.x(), row.y(), row.z(), Ids.shortId(row.world())), ChatFormatting.GRAY));
        if (row.contentsCount() > 0 && row.contents() != null) {
            lore.add(line("Contained %d items: %s".formatted(row.contentsCount(), row.contents()), ChatFormatting.GOLD));
        }
        if (NetworkStorages.DATA_MARKER.equals(row.data())) {
            lore.add(line("Via storage network terminal", ChatFormatting.AQUA));
        } else if (ItemStorages.isMarker(row.data())) {
            lore.add(line("In " + ItemStorages.describe(row.data()), ChatFormatting.AQUA));
        }
        if (row.rolledBack()) {
            lore.add(line("Rolled back", ChatFormatting.RED));
        }
        lore.add(Component.empty());
        lore.add(hint("Click: teleport"));
        lore.add(hint("Right-click: only this player"));
        lore.add(hint("Shift+click: " + (row.rolledBack() ? "restore" : "roll back") + " this entry"));
        lore.add(hint("Shift+right-click: only this block/item"));
        return decorate(stack, name, lore);
    }

    private static Item iconOf(LookupRow row) {
        ResourceLocation id = ResourceLocation.tryParse(row.material());
        Item item = Items.AIR;
        if (id != null) {
            boolean block = row.type() == ActionType.BLOCK_BREAK || row.type() == ActionType.BLOCK_PLACE;
            item = block
                    ? BuiltInRegistries.BLOCK.getOptional(id).map(Block::asItem).orElse(Items.AIR)
                    : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
        }
        // Blocks without an item form (fire, water, ...) and unknown ids
        return item == Items.AIR ? Items.PAPER : item;
    }

    private interface Label {
        String of(int index);
    }

    private static List<Component> options(int count, int selected, Label label) {
        List<Component> lore = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lore.add(i == selected ? line("▶ " + label.of(i), ChatFormatting.YELLOW) : line("  " + label.of(i), ChatFormatting.GRAY));
        }
        lore.add(hint("Click: next, right-click: previous"));
        return lore;
    }

    private static List<Component> filterLore(boolean empty, String what, String... howToSet) {
        List<Component> lore = new ArrayList<>();
        lore.add(line(what, ChatFormatting.GRAY));
        for (String how : howToSet) {
            lore.add(hint(how));
        }
        if (!empty) {
            lore.add(hint("Click: clear"));
        }
        return lore;
    }

    private static List<Component> withHint(List<Component> lore, String text) {
        List<Component> copy = new ArrayList<>(lore);
        copy.add(hint(text));
        return copy;
    }

    private static ItemStack button(Item icon, String title, List<Component> lore) {
        return decorate(new ItemStack(icon), Component.literal(title).withStyle(ChatFormatting.WHITE), lore);
    }

    private static ItemStack decorate(ItemStack stack, MutableComponent name, List<Component> lore) {
        stack.set(DataComponents.CUSTOM_NAME, name.withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(lore.stream()
                .map(c -> (Component) c.copy().withStyle(s -> s.withItalic(false)))
                .toList()));
        stack.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
        return stack;
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }

    private static Component hint(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    // ---- Menu ----

    private static final class Menu extends ChestMenu {
        private final LogBrowser browser;

        Menu(int id, Inventory inventory, LogBrowser browser) {
            super(MenuType.GENERIC_9x6, id, inventory, browser.container, ROWS);
            this.browser = browser;
            browser.menu = this;
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (slotId >= 0 && slotId < ROWS * 9 && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)) {
                browser.onClick(slotId, button, clickType);
            }
            // Nothing ever moves: resend everything to undo what the client predicted
            if (!browser.closed) {
                sendAllDataToRemote();
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            browser.closed = true;
        }
    }
}
