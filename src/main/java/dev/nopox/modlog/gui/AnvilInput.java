package dev.nopox.modlog.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.function.Consumer;

/**
 * Text input through the anvil rename field, without a client mod. The player edits the name of a paper and clicks
 * the result to confirm; closing the anvil cancels. Nothing in it can be taken.
 */
public final class AnvilInput {
    private static final int RESULT_SLOT = 2;

    private AnvilInput() {
    }

    /**
     * Opens the input on the next tick (it's usually requested from inside another menu's click).
     *
     * @param onText called on the main thread with the trimmed text, or null if the player cancelled
     */
    public static void open(ServerPlayer player, String prompt, String initial, Consumer<String> onText) {
        later(player, () -> player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new Menu(id, inventory, player, initial, onText), Component.literal(prompt))));
    }

    /** Runs a task on the main thread after the current packet/menu handling is over. */
    private static void later(ServerPlayer player, Runnable task) {
        player.server.tell(new TickTask(player.server.getTickCount(), task));
    }

    private static final class Menu extends AnvilMenu {
        private final ServerPlayer player;
        private final Consumer<String> onText;
        private String text;
        private boolean finished;

        Menu(int id, Inventory inventory, ServerPlayer player, String initial, Consumer<String> onText) {
            super(id, inventory, ContainerLevelAccess.NULL);
            this.player = player;
            this.onText = onText;
            this.text = initial;
            ItemStack paper = new ItemStack(Items.PAPER);
            // The client pre-fills the rename field with this name
            paper.set(DataComponents.CUSTOM_NAME, Component.literal(initial));
            inputSlots.setItem(0, paper);
        }

        @Override
        public boolean setItemName(String name) {
            text = name;
            createResult();
            // The client predicts a rename cost of its own; resend ours (none)
            sendAllDataToRemote();
            return true;
        }

        @Override
        public void createResult() {
            ItemStack result = new ItemStack(Items.LIME_DYE);
            result.set(DataComponents.CUSTOM_NAME, Component.literal(text.isBlank() ? "(empty)" : text)
                    .withStyle(s -> s.withItalic(false)));
            result.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Click to apply")
                    .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GREEN)))));
            resultSlots.setItem(0, result);
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player p) {
            if (slotId == RESULT_SLOT && !finished) {
                finished = true;
                String value = text.trim();
                later(player, () -> {
                    player.closeContainer();
                    onText.accept(value);
                });
            }
            // Nothing ever moves: resend everything to undo what the client predicted
            sendAllDataToRemote();
        }

        @Override
        protected boolean mayPickup(Player p, boolean hasStack) {
            return false;
        }

        @Override
        public boolean stillValid(Player p) {
            return true;
        }

        @Override
        public void removed(Player p) {
            // Never hand the paper or the result to the player
            inputSlots.clearContent();
            resultSlots.clearContent();
            super.removed(p);
            if (!finished) {
                finished = true;
                later(player, () -> onText.accept(null));
            }
        }
    }
}
