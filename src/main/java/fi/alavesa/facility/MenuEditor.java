package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The admin chest-GUI editor for the MAIN menu contents. Opens a chest listing
 * every {@link MenuElement} as an item (label as the item name, type/action in
 * the lore). Clicking an element SELECTS it (highlighted); border controls then
 * act on the selection: move up/down, delete, or RENAME (opens an anvil - type
 * the new label, take the result to apply). Two add controls append a new
 * button or text element. Every edit writes through {@link MenuStore} and
 * refreshes the view.
 *
 * All GUIs are recognised by their {@link InventoryHolder} so clicks elsewhere
 * are never intercepted. Admin-only (guarded before opening).
 */
public final class MenuEditor implements Listener {

    private final FacilityPlugin plugin;
    private final MenuStore store;

    // Control slots along the bottom row of a 6-row (54-slot) chest.
    private static final int SLOT_MOVE_UP = 45;
    private static final int SLOT_MOVE_DOWN = 46;
    private static final int SLOT_RENAME = 48;
    private static final int SLOT_DELETE = 50;
    private static final int SLOT_ADD_BUTTON = 52;
    private static final int SLOT_ADD_TEXT = 53;
    private static final int LIST_SIZE = 45;   // slots 0..44 list elements

    public MenuEditor(FacilityPlugin plugin, MenuStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    // --- holders ------------------------------------------------------------

    /** The chest editor view; carries the currently selected element index. */
    private static final class EditorHolder implements InventoryHolder {
        Inventory inventory;
        int selected = -1;
        @Override public Inventory getInventory() { return inventory; }
    }

    /** The anvil rename view; remembers which element is being renamed. */
    private static final class RenameHolder implements InventoryHolder {
        Inventory inventory;
        final int index;
        RenameHolder(int index) { this.index = index; }
        @Override public Inventory getInventory() { return inventory; }
    }

    // --- open ---------------------------------------------------------------

    public void open(Player player) {
        open(player, -1);
    }

    private void open(Player player, int selected) {
        EditorHolder holder = new EditorHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
            Component.text("Menu Editor", NamedTextColor.DARK_AQUA));
        holder.inventory = inv;
        holder.selected = selected;
        render(holder);
        player.openInventory(inv);
    }

    private void render(EditorHolder holder) {
        Inventory inv = holder.inventory;
        inv.clear();
        List<MenuElement> els = store.elements();
        for (int i = 0; i < els.size() && i < LIST_SIZE; i++) {
            inv.setItem(i, elementItem(els.get(i), i, i == holder.selected));
        }
        // border controls
        inv.setItem(SLOT_MOVE_UP, control(Material.ARROW, "&aMove Up",
            "Move the selected element up."));
        inv.setItem(SLOT_MOVE_DOWN, control(Material.ARROW, "&aMove Down",
            "Move the selected element down."));
        inv.setItem(SLOT_RENAME, control(Material.NAME_TAG, "&eRename",
            "Rename the selected element (opens an anvil)."));
        inv.setItem(SLOT_DELETE, control(Material.BARRIER, "&cDelete",
            "Delete the selected element."));
        inv.setItem(SLOT_ADD_BUTTON, control(Material.LIME_DYE, "&aAdd Button",
            "Append a new button (edit its action with /facility menu setaction)."));
        inv.setItem(SLOT_ADD_TEXT, control(Material.PAPER, "&fAdd Text",
            "Append a new text line."));
    }

    private ItemStack elementItem(MenuElement el, int index, boolean selected) {
        boolean button = el.type() == MenuElement.Type.BUTTON;
        Material mat = selected ? Material.ENCHANTED_BOOK
            : (button ? Material.OAK_BUTTON : Material.PAPER);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(legacy((selected ? "&e> " : "&7#" + (index + 1) + " ")
            + (el.label() == null || el.label().isBlank() ? "&8(blank)" : el.label())));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Type: " + (button ? "button" : "text")));
        if (button) lore.add(line("Action: /" + el.action()));
        lore.add(line(selected ? "SELECTED" : "Click to select"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack control(Material mat, String name, String desc) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(legacy(name));
        meta.lore(List.of(line(desc)));
        item.setItemMeta(meta);
        return item;
    }

    // --- chest clicks -------------------------------------------------------

    @EventHandler
    public void onEditorClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditorHolder holder)) return;
        event.setCancelled(true);   // it's a control panel, never a loot chest
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
            || !(event.getClickedInventory().getHolder() instanceof EditorHolder)) return;

        int slot = event.getSlot();
        List<MenuElement> els = store.elements();

        // clicking a list item selects it
        if (slot < LIST_SIZE) {
            if (slot >= els.size()) return;   // empty list slot
            holder.selected = slot;
            render(holder);
            return;
        }

        switch (slot) {
            case SLOT_MOVE_UP -> {
                if (validSel(holder, els) && store.move(holder.selected, -1)) {
                    holder.selected = Math.max(0, holder.selected - 1);
                }
                render(holder);
            }
            case SLOT_MOVE_DOWN -> {
                if (validSel(holder, els) && store.move(holder.selected, +1)) {
                    holder.selected = Math.min(store.size() - 1, holder.selected + 1);
                }
                render(holder);
            }
            case SLOT_DELETE -> {
                if (validSel(holder, els)) {
                    store.remove(holder.selected);
                    holder.selected = -1;
                }
                render(holder);
            }
            case SLOT_RENAME -> {
                if (validSel(holder, els)) {
                    openRename(player, holder.selected, els.get(holder.selected));
                }
            }
            case SLOT_ADD_BUTTON -> {
                store.add(MenuElement.button("&aNew Button", "menu"));
                holder.selected = store.size() - 1;
                render(holder);
            }
            case SLOT_ADD_TEXT -> {
                store.add(MenuElement.text("&7New line"));
                holder.selected = store.size() - 1;
                render(holder);
            }
            default -> { /* dead border slot */ }
        }
    }

    private boolean validSel(EditorHolder holder, List<MenuElement> els) {
        return holder.selected >= 0 && holder.selected < els.size();
    }

    // --- anvil rename -------------------------------------------------------

    private void openRename(Player player, int index, MenuElement el) {
        RenameHolder holder = new RenameHolder(index);
        Inventory inv = Bukkit.createInventory(holder, InventoryType.ANVIL,
            Component.text("Rename element", NamedTextColor.DARK_AQUA));
        holder.inventory = inv;
        // Seed the left slot with a name tag prefilled with the current label so
        // the anvil field shows it; the player edits and takes the result.
        ItemStack tag = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = tag.getItemMeta();
        String current = el.label() == null ? "" : el.label();
        meta.itemName(legacy(current.isBlank() ? "label" : current));
        tag.setItemMeta(meta);
        inv.setItem(0, tag);
        player.openInventory(inv);
    }

    /** Keep the anvil's output enabled even with no XP / same text so the
     *  player can always take the result to apply. */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof RenameHolder)) return;
        AnvilInventory anvil = event.getInventory();
        ItemStack left = anvil.getItem(0);
        if (left == null) return;
        String text = anvil.getRenameText();
        ItemStack result = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = result.getItemMeta();
        meta.itemName(legacy(text == null || text.isBlank() ? "label" : text));
        result.setItemMeta(meta);
        event.setResult(result);
    }

    @EventHandler
    public void onRenameClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RenameHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() != 2) return;   // only the anvil OUTPUT slot applies
        AnvilInventory anvil = (AnvilInventory) event.getInventory();
        String text = anvil.getRenameText();
        if (text == null) text = "";
        // Store the raw typed text (supports & colour codes) as the new label.
        boolean ok = store.setLabel(holder.index, text);
        player.closeInventory();
        if (ok) {
            player.sendMessage(Component.text("Label updated. Menu refreshed.",
                NamedTextColor.AQUA));
        } else {
            player.sendMessage(Component.text("That element no longer exists.",
                NamedTextColor.RED));
        }
        // Reopen the chest editor on the same element so editing keeps flowing.
        final int idx = holder.index;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) open(player, idx < store.size() ? idx : -1);
        });
    }

    // --- helpers ------------------------------------------------------------

    private Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s)
            .decoration(TextDecoration.ITALIC, false);
    }

    private Component line(String s) {
        return Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
