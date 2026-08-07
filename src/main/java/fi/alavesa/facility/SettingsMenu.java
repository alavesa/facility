package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The in-inventory UI hub. Three unmovable buttons live in the player's inventory:
 *   - {@code SETTINGS_SLOT} → Settings (Sound Quality toggle; HIGH custom / LOW vanilla, default HIGH,
 *     stored under the shared {@code scp:sound_quality} key the Guns plugin reads);
 *   - {@code GUNSTATS_SLOT} → Gun Stats index: every gun/attachment the player has ever picked up,
 *     unlocked as they're found (locked ones simply aren't shown);
 *   - {@code PLAYERLIST_SLOT} → a player list with each online player's head + ping.
 * The survival recipe book is also emptied and kept empty (non-functional).
 */
public final class SettingsMenu implements Listener {

    public static final NamespacedKey SOUND_KEY = new NamespacedKey("scp", "sound_quality");
    private static final NamespacedKey GUNS_ID = new NamespacedKey("guns", "id");
    private static final NamespacedKey GUNS_ATT_ID = new NamespacedKey("guns", "attachment_id");

    private static final int SETTINGS_SLOT = 17, GUNSTATS_SLOT = 16, PLAYERLIST_SLOT = 15;
    private static final int SOUND_SLOT = 11;
    private static final char FS = '\u001F', RS = '\u001E';   // field / record separators for the unlock store

    private final FacilityPlugin plugin;
    private final NamespacedKey buttonKey;   // BYTE: marks a UI button
    private final NamespacedKey typeKey;     // STRING: which button
    private final NamespacedKey unlocksKey;  // STRING: serialized unlocked gun/attachment index

    public SettingsMenu(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.buttonKey = new NamespacedKey(plugin, "ui_button");
        this.typeKey = new NamespacedKey(plugin, "ui_type");
        this.unlocksKey = new NamespacedKey(plugin, "unlocks");
    }

    public static String soundQuality(Player p) {
        return p.getPersistentDataContainer().getOrDefault(SOUND_KEY, PersistentDataType.STRING, "high");
    }

    // ---------------------------------------------------------------- recipe book off + buttons

    @EventHandler public void onDiscover(PlayerRecipeDiscoverEvent event) { event.setCancelled(true); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        p.undiscoverRecipes(new ArrayList<>(p.getDiscoveredRecipes()));
        giveButtons(p);
        scanUnlocks(p);
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> giveButtons(event.getPlayer()), 2L);
    }

    @EventHandler public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player p) scanUnlocks(p);
    }

    @EventHandler public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p)
            Bukkit.getScheduler().runTaskLater(plugin, () -> scanUnlocks(p), 1L);
    }

    private void giveButtons(Player p) {
        p.getInventory().setItem(SETTINGS_SLOT, button("settings", Material.COMPARATOR, "⚙ Settings", "ui_settings", "Open settings."));
        p.getInventory().setItem(GUNSTATS_SLOT, button("gunstats", Material.BOOK, "🔫 Gun Stats", "ui_gunstats", "Guns & attachments you've unlocked."));
        p.getInventory().setItem(PLAYERLIST_SLOT, button("playerlist", Material.NAME_TAG, "👥 Player List", "ui_playerlist", "Who's online + their ping."));
    }

    private ItemStack button(String type, Material mat, String name, String model, String lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(Component.text(name, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        setModel(meta, model);
        meta.getPersistentDataContainer().set(buttonKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isButton(ItemStack it) {
        return it != null && it.hasItemMeta()
            && it.getItemMeta().getPersistentDataContainer().has(buttonKey, PersistentDataType.BYTE);
    }
    private boolean isButtonSlot(int slot) {
        return slot == SETTINGS_SLOT || slot == GUNSTATS_SLOT || slot == PLAYERLIST_SLOT;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;

        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof SettingsHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() == SOUND_SLOT) toggleSound(p);
            return;
        }
        if (holder instanceof IndexHolder || holder instanceof PlayerlistHolder) { event.setCancelled(true); return; }

        boolean ownInv = event.getClickedInventory() != null && event.getClickedInventory().equals(p.getInventory());
        if (ownInv && isButtonSlot(event.getSlot()) && isButton(event.getCurrentItem())) {
            event.setCancelled(true);
            String type = event.getCurrentItem().getItemMeta().getPersistentDataContainer()
                .getOrDefault(typeKey, PersistentDataType.STRING, "settings");
            switch (type) {
                case "gunstats" -> openIndex(p);
                case "playerlist" -> openPlayerlist(p);
                default -> openSettings(p);
            }
            return;
        }
        if (isButton(event.getCurrentItem()) || isButton(event.getCursor())) { event.setCancelled(true); return; }
        if (event.getClick() == ClickType.NUMBER_KEY && ownInv && isButtonSlot(event.getSlot())) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked().getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) return;
        for (int s : event.getRawSlots()) if (isButtonSlot(s)) { event.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        ensure(p, SETTINGS_SLOT, "settings"); ensure(p, GUNSTATS_SLOT, "gunstats"); ensure(p, PLAYERLIST_SLOT, "playerlist");
    }

    private void ensure(Player p, int slot, String type) {
        ItemStack at = p.getInventory().getItem(slot);
        if (!isButton(at)) {
            if (at != null && at.getType() != Material.AIR) p.getInventory().addItem(at);
            giveButtons(p);
        }
    }

    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isButton);
    }

    // ---------------------------------------------------------------- Settings GUI

    public void openSettings(Player p) {
        Inventory inv = frame(new SettingsHolder(), "Settings");
        inv.setItem(SOUND_SLOT, soundItem(p));
        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    private void toggleSound(Player p) {
        String next = soundQuality(p).equals("high") ? "low" : "high";
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
            Component.text((high ? "▸ " : "  ") + "HIGH — custom sound pack", high ? NamedTextColor.GREEN : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text((!high ? "▸ " : "  ") + "LOW — vanilla sounds", !high ? NamedTextColor.YELLOW : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Click to change.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        setModel(meta, high ? "ui_sound_high" : "ui_sound_low");
        it.setItemMeta(meta);
        return it;
    }

    // ---------------------------------------------------------------- Gun Stats index

    /** Record everything gun/attachment the player is carrying that they haven't unlocked yet. */
    private void scanUnlocks(Player p) {
        var pdc = p.getPersistentDataContainer();
        String raw = pdc.getOrDefault(unlocksKey, PersistentDataType.STRING, "");
        Set<String> keys = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder(raw);
        if (!raw.isEmpty()) for (String rec : raw.split(String.valueOf(RS))) {
            String[] f = rec.split(String.valueOf(FS));
            if (f.length >= 2) keys.add(f[1] + ":" + f[0]);   // type:id
        }
        boolean changed = false;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            var ip = it.getItemMeta().getPersistentDataContainer();
            String gid = ip.get(GUNS_ID, PersistentDataType.STRING);
            String aid = ip.get(GUNS_ATT_ID, PersistentDataType.STRING);
            String id, type;
            if (gid != null) { id = gid; type = "gun"; }
            else if (aid != null) { id = aid; type = "attachment"; }
            else continue;
            if (!keys.add(type + ":" + id)) continue;   // already unlocked
            String name = PlainTextComponentSerializer.plainText().serialize(
                it.getItemMeta().hasItemName() ? it.getItemMeta().itemName() : Component.text(id));
            String cmd = firstModel(it.getItemMeta());
            if (sb.length() > 0) sb.append(RS);
            sb.append(id).append(FS).append(type).append(FS).append(it.getType().name()).append(FS).append(cmd).append(FS).append(name);
            changed = true;
        }
        if (changed) pdc.set(unlocksKey, PersistentDataType.STRING, sb.toString());
    }

    public void openIndex(Player p) {
        Inventory inv = frame(new IndexHolder(), "Gun Stats — Index");
        String raw = p.getPersistentDataContainer().getOrDefault(unlocksKey, PersistentDataType.STRING, "");
        int slot = 10;
        if (!raw.isEmpty()) for (String rec : raw.split(String.valueOf(RS))) {
            String[] f = rec.split(String.valueOf(FS), -1);
            if (f.length < 5) continue;
            Material mat;
            try { mat = Material.valueOf(f[2]); } catch (IllegalArgumentException e) { mat = Material.PAPER; }
            ItemStack entry = new ItemStack(mat);
            ItemMeta meta = entry.getItemMeta();
            meta.itemName(Component.text(f[4].isEmpty() ? f[0] : f[4],
                f[1].equals("gun") ? NamedTextColor.GOLD : NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(f[1].equals("gun") ? "Gun" : "Attachment", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            if (!f[3].isEmpty()) setModel(meta, f[3]);
            entry.setItemMeta(meta);
            while (slot < 44 && (slot % 9 == 0 || slot % 9 == 8)) slot++;   // keep off the border
            if (slot >= 44) break;
            inv.setItem(slot++, entry);
        }
        if (slot == 10) inv.setItem(22, named(Material.BARRIER, "Nothing unlocked yet — pick up a gun."));
        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.2f);
    }

    // ---------------------------------------------------------------- Player list

    public void openPlayerlist(Player viewer) {
        var online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int rows = Math.max(1, Math.min(6, (online.size() + 8) / 9));
        Inventory inv = Bukkit.createInventory(new PlayerlistHolder(), rows * 9,
            Component.text("Online — " + online.size(), NamedTextColor.DARK_AQUA));
        int slot = 0;
        for (Player pl : online) {
            if (slot >= inv.getSize()) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(pl);
            sm.itemName(Component.text(pl.getName(), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            int ping = pl.getPing();
            NamedTextColor c = ping < 80 ? NamedTextColor.GREEN : ping < 200 ? NamedTextColor.YELLOW : NamedTextColor.RED;
            sm.lore(List.of(Component.text("Ping: " + ping + " ms", c).decoration(TextDecoration.ITALIC, false)));
            head.setItemMeta(sm);
            inv.setItem(slot++, head);
        }
        viewer.openInventory(inv);
        viewer.playSound(viewer.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
    }

    // ---------------------------------------------------------------- helpers

    private Inventory frame(InventoryHolder holder, String title) {
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text(title, NamedTextColor.DARK_AQUA));
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
        return inv;
    }

    private ItemStack named(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.itemName(Component.text(name, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    private void setModel(ItemMeta meta, String model) {
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        meta.setCustomModelDataComponent(cmd);
    }

    private String firstModel(ItemMeta meta) {
        var s = meta.getCustomModelDataComponent().getStrings();
        return s.isEmpty() ? "" : s.get(0);
    }

    private static final class SettingsHolder implements InventoryHolder { public Inventory getInventory() { return null; } }
    private static final class IndexHolder implements InventoryHolder { public Inventory getInventory() { return null; } }
    private static final class PlayerlistHolder implements InventoryHolder { public Inventory getInventory() { return null; } }
}
