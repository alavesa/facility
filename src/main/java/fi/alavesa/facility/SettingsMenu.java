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
    private static final NamespacedKey GUNS_GUN_ATTACHMENTS = new NamespacedKey("guns", "gun_attachments"); // CSV on a gun

    private static final int SETTINGS_SLOT = 17, GUNSTATS_SLOT = 16, PLAYERLIST_SLOT = 15;
    private static final int SOUND_SLOT = 11;
    private static final char FS = '\u001F', RS = '\u001E';   // field / record separators for the unlock store

    private final FacilityPlugin plugin;
    private final NamespacedKey buttonKey;   // BYTE: marks a UI button
    private final NamespacedKey typeKey;     // STRING: which button
    private final NamespacedKey unlocksKey;  // STRING: serialized unlocked gun/attachment index
    private final NamespacedKey attEntryKey; // STRING: attachment id carried by a Gun-Stats GUI entry

    public SettingsMenu(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.buttonKey = new NamespacedKey(plugin, "ui_button");
        this.typeKey = new NamespacedKey(plugin, "ui_type");
        this.unlocksKey = new NamespacedKey(plugin, "unlocks");
        this.attEntryKey = new NamespacedKey(plugin, "att_entry");   // attachment id on a Gun-Stats entry
        // Bulletproof fix for "the settings button sticks to the cursor": opening your OWN inventory
        // (pressing E) does NOT fire InventoryOpenEvent, so clearing on open never ran. A light sweep
        // clears any UI button left on any player's cursor, whatever put it there.
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) clearCursorButton(p);
        }, 20L, 3L);
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
        if (event.getPlayer() instanceof Player p) { scanUnlocks(p); clearCursorButton(p); }
    }

    /** Clear a UI button that is actually on the SERVER cursor (only then re-sync, so the 3-tick sweep
     *  doesn't spam updateInventory to everyone). The client-only "ghost" from a cancelled pickup is
     *  handled separately, per-click, in onClick. */
    private void clearCursorButton(Player p) {
        if (isButton(p.getItemOnCursor())) { p.setItemOnCursor(null); p.updateInventory(); }
    }

    @EventHandler public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p)
            Bukkit.getScheduler().runTaskLater(plugin, () -> scanUnlocks(p), 1L);
    }

    private void giveButtons(Player p) {
        placeButton(p, SETTINGS_SLOT, button("settings", Material.COMPARATOR, "⚙ Settings", "ui_settings", "Open settings."));
        placeButton(p, GUNSTATS_SLOT, button("gunstats", Material.BOOK, "🔫 Gun Stats", "ui_gunstats", "Guns & attachments you've unlocked."));
        placeButton(p, PLAYERLIST_SLOT, button("playerlist", Material.NAME_TAG, "👥 Player List", "ui_playerlist", "Who's online + their ping."));
    }

    /** Place a button WITHOUT ever destroying a player's item. If the slot holds a real item we relocate
     *  it first and only claim the slot if it fully moved; if the inventory is full we keep the item and
     *  skip the button. (The old code overwrote the slot outright, which deleted items - the "items
     *  disappear from the inventory" bug.) */
    private void placeButton(Player p, int slot, ItemStack btn) {
        ItemStack at = p.getInventory().getItem(slot);
        if (isButton(at)) { p.getInventory().setItem(slot, btn); return; }   // refresh an existing button
        if (at != null && at.getType() != Material.AIR) {
            var leftover = p.getInventory().addItem(at.clone());             // move the player's item elsewhere
            if (!leftover.isEmpty()) return;                                 // inventory full -> keep item, no button
            p.getInventory().setItem(slot, btn);
        } else {
            p.getInventory().setItem(slot, btn);                            // slot was empty
        }
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

        // (0) UI BUTTONS are handled FIRST, in EVERY context. A button can never be picked up, and
        //     clicking one always opens its menu - even while you're already inside another menu. (The
        //     holder checks below used to run first and 'return', so a button clicked from the lower
        //     inventory while a menu was open did nothing AND stuck to the cursor - that's the bug.)
        //     The menu is opened on the next tick so it never races the current click event, and any
        //     stray button on the cursor is cleared.
        ItemStack cur = event.getCurrentItem();
        boolean cursorIsButton = isButton(event.getCursor());
        if (isButton(cur) || cursorIsButton) {
            event.setCancelled(true);
            event.setCursor(null);                 // never let the click leave a button on the cursor
            if (isButton(cur) && !cursorIsButton
                && event.getClickedInventory() != null && event.getClickedInventory().equals(p.getInventory())) {
                String type = cur.getItemMeta().getPersistentDataContainer()
                    .getOrDefault(typeKey, PersistentDataType.STRING, "settings");
                Bukkit.getScheduler().runTask(plugin, () -> openByType(p, type));
            }
            // The client already drew the button on the cursor even though the server cancelled it -
            // re-sync next tick so that ghost is wiped.
            Bukkit.getScheduler().runTask(plugin, () -> { clearCursorButton(p); p.updateInventory(); });
            return;
        }

        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof SettingsHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() == SOUND_SLOT) toggleSound(p);
            return;
        }
        if (holder instanceof IndexHolder) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            String attId = clicked == null || !clicked.hasItemMeta() ? null
                : clicked.getItemMeta().getPersistentDataContainer().get(attEntryKey, PersistentDataType.STRING);
            if (attId != null) toggleAttachment(p, attId);   // add/remove on the held gun via the Guns command
            return;
        }
        if (holder instanceof PlayerlistHolder) { event.setCancelled(true); return; }

        boolean ownInv = event.getClickedInventory() != null && event.getClickedInventory().equals(p.getInventory());
        if (event.getClick() == ClickType.NUMBER_KEY && ownInv && isButtonSlot(event.getSlot())) { event.setCancelled(true); return; }
        // (The old SCP:CB right-click-to-equip gesture was removed per request - normal vanilla
        //  inventory handling only.)
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked().getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) return;
        for (int s : event.getRawSlots()) if (isButtonSlot(s)) { event.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        giveButtons(p);   // non-destructive: re-places only missing buttons, never deletes items
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> clearCursorButton(p));   // never leave a button on the cursor
    }

    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isButton);
    }

    // ---------------------------------------------------------------- Settings GUI

    private void openByType(Player p, String type) {
        switch (type) {
            case "gunstats" -> openIndex(p);
            case "playerlist" -> openPlayerlist(p);
            default -> openSettings(p);
        }
    }

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

    /** Attachment ids currently on the player's held gun (read straight off the Guns CSV tag). */
    private Set<String> heldGunAttachments(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held == null || !held.hasItemMeta()) return java.util.Collections.emptySet();
        String csv = held.getItemMeta().getPersistentDataContainer()
            .getOrDefault(GUNS_GUN_ATTACHMENTS, PersistentDataType.STRING, "");
        if (csv.isEmpty()) return java.util.Collections.emptySet();
        return new LinkedHashSet<>(java.util.Arrays.asList(csv.split(",")));
    }
    private boolean holdingGun(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        return held != null && held.hasItemMeta()
            && held.getItemMeta().getPersistentDataContainer().has(GUNS_ID, PersistentDataType.STRING);
    }

    /** Add or remove an attachment on the held gun by reusing the Guns plugin's /guns attach|detach
     *  command (which owns the attachment logic + model refresh), then refresh the index view. */
    private void toggleAttachment(Player p, String attId) {
        if (!holdingGun(p)) { p.sendMessage(Component.text("Hold a gun to fit attachments.", NamedTextColor.RED)); return; }
        boolean fitted = heldGunAttachments(p).contains(attId.toLowerCase());
        p.performCommand("guns " + (fitted ? "detach " : "attach ") + attId);
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_PISTON_CONTRACT, 0.6f, fitted ? 0.8f : 1.4f);
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) openIndex(p); });
    }

    public void openIndex(Player p) {
        Inventory inv = frame(new IndexHolder(), "Gun Stats — Index");
        boolean gun = holdingGun(p);
        Set<String> onGun = heldGunAttachments(p);
        String raw = p.getPersistentDataContainer().getOrDefault(unlocksKey, PersistentDataType.STRING, "");
        int slot = 10;
        if (!raw.isEmpty()) for (String rec : raw.split(String.valueOf(RS))) {
            String[] f = rec.split(String.valueOf(FS), -1);
            if (f.length < 5) continue;
            boolean isAtt = f[1].equals("attachment");
            boolean fitted = isAtt && onGun.contains(f[0].toLowerCase());
            Material mat;
            try { mat = Material.valueOf(f[2]); } catch (IllegalArgumentException e) { mat = Material.PAPER; }
            ItemStack entry = new ItemStack(mat);
            ItemMeta meta = entry.getItemMeta();
            meta.itemName(Component.text(f[4].isEmpty() ? f[0] : f[4],
                f[1].equals("gun") ? NamedTextColor.GOLD : NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(isAtt ? "Attachment" : "Gun", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            if (isAtt) {
                lore.add(Component.text(!gun ? "Hold a gun to fit this"
                        : fitted ? "✔ Fitted — click to remove" : "Click to attach to your gun",
                    !gun ? NamedTextColor.DARK_GRAY : fitted ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
                meta.getPersistentDataContainer().set(attEntryKey, PersistentDataType.STRING, f[0].toLowerCase());
                if (fitted) meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            meta.lore(lore);
            if (!f[3].isEmpty()) setModel(meta, f[3]);
            entry.setItemMeta(meta);
            while (slot < 44 && (slot % 9 == 0 || slot % 9 == 8)) slot++;   // keep off the border
            if (slot >= 44) break;
            inv.setItem(slot++, entry);
        }
        if (slot == 10) inv.setItem(22, named(Material.BARRIER, "Nothing unlocked yet — pick up a gun."));
        inv.setItem(49, named(Material.PAPER, gun ? "Attachments apply to the gun in your hand"
            : "Hold a gun, then click an attachment to fit/remove it"));
        p.openInventory(inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1.2f);
    }

    // ---------------------------------------------------------------- Player list

    public void openPlayerlist(Player viewer) {
        var online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int rows = Math.max(1, Math.min(6, (online.size() + 8) / 9));
        Inventory inv = Bukkit.createInventory(new PlayerlistHolder(), rows * 9,
            guiTitle("Online — " + online.size()));
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
        Inventory inv = Bukkit.createInventory(holder, 27, guiTitle(title));
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
        return inv;
    }

    /** GUI title, optionally prefixed with a custom-font background glyph so the menu can be given a
     *  custom TEXTURE via a resource pack (set gui.font + gui.background-glyph in config.yml, e.g. a
     *  negative-space glyph that draws the panel behind the title). Plain text if not configured. */
    private Component guiTitle(String text) {
        String font = plugin.getConfig().getString("gui.font", "");
        String glyph = plugin.getConfig().getString("gui.background-glyph", "");
        Component label = Component.text(text, NamedTextColor.DARK_AQUA);
        if (font.isEmpty() || glyph.isEmpty()) return label;
        return Component.text(glyph).font(net.kyori.adventure.key.Key.key(font)).append(label);
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
