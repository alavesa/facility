package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The in-inventory Settings system:
 *   - the survival recipe book is emptied and kept empty, so it's non-functional;
 *   - one inventory tile (slot {@link #BUTTON_SLOT}) is an unmovable Settings button;
 *   - clicking it opens a Settings GUI whose first control is a Sound Quality toggle
 *     (HIGH = custom sound pack, LOW = vanilla sounds; default HIGH). The choice is stored on the
 *     player under the SHARED {@code scp:sound_quality} key so the Guns plugin can read it.
 */
public final class SettingsMenu implements Listener {

    /** Shared across plugins (Guns reads this). Namespace is a literal so both agree on the key. */
    public static final NamespacedKey SOUND_KEY = new NamespacedKey("scp", "sound_quality");
    private static final int BUTTON_SLOT = 17;   // right edge of the top storage row

    private final FacilityPlugin plugin;
    private final NamespacedKey buttonKey;

    public SettingsMenu(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.buttonKey = new NamespacedKey(plugin, "ui_button");
    }

    public static String soundQuality(Player p) {
        return p.getPersistentDataContainer().getOrDefault(SOUND_KEY, PersistentDataType.STRING, "high");
    }

    // ---------------------------------------------------------------- recipe book off

    @EventHandler
    public void onDiscover(PlayerRecipeDiscoverEvent event) {
        event.setCancelled(true);   // nothing ever enters the recipe book -> it stays empty/non-functional
    }

    // ---------------------------------------------------------------- the inventory button

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        p.undiscoverRecipes(new java.util.ArrayList<>(p.getDiscoveredRecipes()));   // clear any known recipes
        giveButton(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> giveButton(event.getPlayer()), 2L);
    }

    private void giveButton(Player p) {
        p.getInventory().setItem(BUTTON_SLOT, buttonItem());
    }

    private ItemStack buttonItem() {
        ItemStack it = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(Component.text("⚙ Settings", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Click to open settings.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        model(meta, "ui_settings");
        meta.getPersistentDataContainer().set(buttonKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isButton(ItemStack it) {
        return it != null && it.hasItemMeta()
            && it.getItemMeta().getPersistentDataContainer().has(buttonKey, PersistentDataType.BYTE);
    }

    /** Keep the button put: open settings on click, and refuse any attempt to move/take it. */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;

        // Settings GUI itself: cancel all, handle the toggle.
        if (event.getInventory().getHolder() instanceof SettingsHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() == SOUND_SLOT) toggleSound(p);
            return;
        }

        boolean ownInv = event.getClickedInventory() != null && event.getClickedInventory().equals(p.getInventory());
        // clicking the button slot in your own inventory
        if (ownInv && event.getSlot() == BUTTON_SLOT) {
            event.setCancelled(true);
            if (event.getClick().isLeftClick() || event.getClick().isRightClick()) openSettings(p);
            return;
        }
        // never let the button be dragged onto the cursor / swapped in via number keys / shift-clicks
        if (isButton(event.getCurrentItem()) || isButton(event.getCursor())) { event.setCancelled(true); return; }
        if (event.getClick() == ClickType.NUMBER_KEY && ownInv && event.getSlot() == BUTTON_SLOT) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getRawSlots().contains(BUTTON_SLOT)
            && event.getWhoClicked().getOpenInventory().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            event.setCancelled(true);   // dragging across the button slot in the survival inventory
        }
    }

    /** Safety net: if the button ever gets displaced, restore it (handing back whatever sat there). */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        ItemStack at = p.getInventory().getItem(BUTTON_SLOT);
        if (!isButton(at)) {
            if (at != null && at.getType() != Material.AIR) p.getInventory().addItem(at);
            giveButton(p);
        }
    }

    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isButton);   // the button never drops
    }

    // ---------------------------------------------------------------- the Settings GUI

    private static final int SOUND_SLOT = 11;

    public void openSettings(Player p) {
        Inventory inv = Bukkit.createInventory(new SettingsHolder(), 27,
            Component.text("Settings", NamedTextColor.DARK_AQUA));
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
        inv.setItem(SOUND_SLOT, soundItem(p));
        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    private void toggleSound(Player p) {
        String now = soundQuality(p);
        String next = now.equals("high") ? "low" : "high";
        p.getPersistentDataContainer().set(SOUND_KEY, PersistentDataType.STRING, next);
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof SettingsHolder)
            p.getOpenInventory().getTopInventory().setItem(SOUND_SLOT, soundItem(p));
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, next.equals("high") ? 1.6f : 0.8f);
    }

    private ItemStack soundItem(Player p) {
        boolean high = soundQuality(p).equals("high");
        ItemStack it = new ItemStack(high ? Material.JUKEBOX : Material.NOTE_BLOCK);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(Component.text("Sound Quality: ", NamedTextColor.WHITE)
            .append(Component.text(high ? "HIGH" : "LOW", high ? NamedTextColor.GREEN : NamedTextColor.YELLOW))
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text(high ? "▸ HIGH — custom sound pack" : "  HIGH — custom sound pack",
                high ? NamedTextColor.GREEN : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text(!high ? "▸ LOW — vanilla sounds" : "  LOW — vanilla sounds",
                !high ? NamedTextColor.YELLOW : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Click to change.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        model(meta, high ? "ui_sound_high" : "ui_sound_low");
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack named(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(Component.text(name, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private void model(ItemMeta meta, String model) {
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
    }

    /** Marks the Settings inventory. */
    private static final class SettingsHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
