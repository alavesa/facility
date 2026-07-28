package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * The op-only starter-kit editor: a double chest laid out like the player inventory. Slots 0-35 are
 * the main inventory (hotbar + storage), and the bottom row holds the four armour pieces and the
 * off-hand. Drop items in, close the window, and it's saved as that team's kit - applied to every
 * member when they deploy or respawn.
 */
public final class KitEditor implements Listener {

    private static final int SIZE = 54;
    // Bottom row (45-53): armour pieces + off-hand, then filler.
    private static final int HELMET = 45, CHEST = 46, LEGS = 47, BOOTS = 48, OFFHAND = 49;
    private static final int INFO = 44;

    private final FacilityPlugin plugin;
    private final KitStore kits;

    public KitEditor(FacilityPlugin plugin, KitStore kits) {
        this.plugin = plugin;
        this.kits = kits;
    }

    public static final class Holder implements InventoryHolder {
        private final String teamId;
        private Inventory inv;
        Holder(String teamId) { this.teamId = teamId; }
        @Override public Inventory getInventory() { return inv; }
    }

    /** Which top-inventory slots are locked decoration (not part of the kit). */
    private boolean isFiller(int slot) {
        return (slot >= 36 && slot <= 43) || (slot >= 50 && slot <= 53) || slot == INFO;
    }

    // --------------------------------------------------------------- open

    public void open(Player admin, String teamId, String display) {
        Holder holder = new Holder(teamId);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
            Component.text("Kit — ", NamedTextColor.DARK_AQUA)
                .append(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                    .legacyAmpersand().deserialize(display)));
        holder.inv = inv;

        ItemStack[] main = kits.mainOf(teamId);
        for (int i = 0; i < KitStore.MAIN_SLOTS; i++) if (main[i] != null) inv.setItem(i, main[i]);
        inv.setItem(HELMET, kits.armorOf(teamId, "helmet"));
        inv.setItem(CHEST, kits.armorOf(teamId, "chestplate"));
        inv.setItem(LEGS, kits.armorOf(teamId, "leggings"));
        inv.setItem(BOOTS, kits.armorOf(teamId, "boots"));
        inv.setItem(OFFHAND, kits.offhandOf(teamId));

        for (int i = 36; i <= 43; i++) inv.setItem(i, pane());
        for (int i = 50; i <= 53; i++) inv.setItem(i, pane());
        inv.setItem(INFO, info());

        admin.openInventory(inv);
    }

    // -------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) return;
        // Lock the decoration slots; everything else (main inventory + armour + off-hand) is free to edit.
        if (event.getClickedInventory() != null
            && event.getClickedInventory().getHolder() instanceof Holder
            && isFiller(event.getRawSlot())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) return;
        for (int raw : event.getRawSlots()) {
            if (raw < SIZE && isFiller(raw)) { event.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        Inventory inv = event.getInventory();
        ItemStack[] main = new ItemStack[KitStore.MAIN_SLOTS];
        for (int i = 0; i < KitStore.MAIN_SLOTS; i++) main[i] = inv.getItem(i);
        kits.save(holder.teamId, main,
            inv.getItem(HELMET), inv.getItem(CHEST), inv.getItem(LEGS), inv.getItem(BOOTS), inv.getItem(OFFHAND));
        if (event.getPlayer() instanceof Player admin) {
            admin.sendMessage(Component.text("Saved the '" + holder.teamId + "' starter kit.",
                NamedTextColor.AQUA));
        }
    }

    // -------------------------------------------------------------- helpers

    private ItemStack pane() {
        return named(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
    }

    private ItemStack info() {
        return named(Material.KNOWLEDGE_BOOK, Component.text("Starter Kit Editor", NamedTextColor.AQUA, TextDecoration.BOLD),
            List.of(
                line("Slots 1-4 rows = the player's inventory.", NamedTextColor.GRAY),
                line("Bottom row: helmet, chest, leggings,", NamedTextColor.GRAY),
                line("boots, off-hand (left to right).", NamedTextColor.GRAY),
                Component.empty(),
                line("Drop items in, then close to save.", NamedTextColor.DARK_GRAY),
                line("Applied when a member deploys/respawns.", NamedTextColor.DARK_GRAY)));
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack named(Material mat, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
