package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The S-Nav (Site Navigator): a handheld, battery-powered tool that shows a real Minecraft MAP of
 * the walls around the holder (see {@link SNavRenderer}) instead of block-display holograms. One
 * shared, contextual map view personalises per holder: it recentres and redraws whenever they walk
 * out of the current area (no battery cost for that). The device drains a battery while held and
 * dies at 0%; sneak + right-click loads the Lab plugin's 9V Battery.
 */
public final class SNavManager implements Listener {

    private final FacilityPlugin plugin;
    private final NamespacedKey snavKey;
    private final NamespacedKey batteryKey;
    private final Map<UUID, Integer> drainCounter = new HashMap<>();

    private MapView view;
    private SNavRenderer renderer;

    public SNavManager(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.snavKey = new NamespacedKey(plugin, "snav");
        this.batteryKey = new NamespacedKey(plugin, "snav_battery");
    }

    private int batteryMax() { return Math.max(1, plugin.getConfig().getInt("snav.battery-max", 100)); }
    private int drainEvery() { return Math.max(1, plugin.getConfig().getInt("snav.battery-drain-seconds", 3)); }
    public int scanY() { return plugin.getConfig().getInt("snav.scan-y", 64); }
    public void setScanY(int y) { plugin.getConfig().set("snav.scan-y", y); plugin.saveConfig(); }

    /** Bind (or reuse) the one shared S-Nav map view and attach our renderer. Called on enable so
     *  S-Navs handed out before a restart keep working (the map id is remembered in config). */
    public void init() {
        int id = plugin.getConfig().getInt("snav.map-id", -1);
        MapView v = id >= 0 ? Bukkit.getMap(id) : null;
        if (v == null) {
            World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (w == null) { plugin.getLogger().warning("[S-Nav] no world to bind the map to."); return; }
            v = Bukkit.createMap(w);
            plugin.getConfig().set("snav.map-id", v.getId());
            plugin.saveConfig();
        }
        v.setScale(MapView.Scale.NORMAL);
        v.setTrackingPosition(false);
        v.setUnlimitedTracking(false);
        for (MapRenderer r : new ArrayList<>(v.getRenderers())) v.removeRenderer(r);
        this.renderer = new SNavRenderer(plugin, this);
        v.addRenderer(renderer);
        this.view = v;
    }

    // --- item ---------------------------------------------------------------

    public ItemStack buildItem() {
        if (view == null) init();
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.itemName(Component.text("S-Nav Navigator", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("A live map of the walls around you.", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Sneak + right-click: load a 9V battery", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(snavKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(batteryKey, PersistentDataType.INTEGER, batteryMax());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSnav(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(snavKey, PersistentDataType.BYTE);
    }

    /** Same 9V battery as the Lab NVGs: any item whose custom_model_data carries "lab_battery". */
    private boolean isBattery(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getCustomModelDataComponent().getStrings().contains("lab_battery");
    }

    public int battery(ItemStack snav) {
        return snav.getItemMeta().getPersistentDataContainer()
            .getOrDefault(batteryKey, PersistentDataType.INTEGER, 0);
    }

    /** Write a new charge WITHOUT disturbing the map view or anything else on the item. */
    private void setBattery(Player p, ItemStack snav, int value) {
        MapMeta meta = (MapMeta) snav.getItemMeta();
        meta.getPersistentDataContainer().set(batteryKey, PersistentDataType.INTEGER,
            Math.max(0, Math.min(batteryMax(), value)));
        snav.setItemMeta(meta);
        p.getInventory().setItemInMainHand(snav);
    }

    // --- interaction --------------------------------------------------------

    /** Sneak + right-click a held S-Nav with a 9V battery in the bag to recharge it. */
    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getAction().isRightClick()) return;
        ItemStack held = event.getItem();
        if (!isSnav(held)) return;
        Player p = event.getPlayer();
        if (!p.isSneaking()) return;   // plain right-click does nothing (the map just shows)
        event.setCancelled(true);
        if (battery(held) >= batteryMax()) {
            Msg.actionbar(p, Component.text("S-Nav battery is already full.", NamedTextColor.GRAY));
            return;
        }
        if (!consumeBattery(p)) {
            Msg.actionbar(p, Component.text("No 9V batteries in your inventory.", NamedTextColor.RED));
            return;
        }
        setBattery(p, held, batteryMax());
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.6f);
        Msg.actionbar(p, Component.text("9V battery loaded — S-Nav at 100%.", NamedTextColor.GREEN));
    }

    private boolean consumeBattery(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isBattery(it)) {
                it.setAmount(it.getAmount() - 1);
                inv.setItem(i, it.getAmount() > 0 ? it : null);
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drainCounter.remove(event.getPlayer().getUniqueId());
        if (renderer != null) renderer.forget(event.getPlayer().getUniqueId());
    }

    // --- battery drain (while held) ----------------------------------------

    public void tick() {
        int threshold = drainEvery() * 10;   // task runs every 2 ticks -> *10 per second-group
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            ItemStack held = p.getInventory().getItemInMainHand();
            UUID id = p.getUniqueId();
            if (!isSnav(held) || battery(held) <= 0) { drainCounter.remove(id); continue; }
            int n = drainCounter.getOrDefault(id, 0) + 1;
            if (n >= threshold) {
                n = 0;
                int left = battery(held) - 1;
                setBattery(p, held, left);
                if (left <= 0) {
                    Msg.actionbar(p, Component.text("S-Nav battery depleted.", NamedTextColor.RED));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 0.8f);
                }
            }
            drainCounter.put(id, n);
        }
    }
}
