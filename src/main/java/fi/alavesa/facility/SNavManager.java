package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The S-Nav (Site Navigator): a handheld, battery-powered tool that projects a floating minimap.
 * It SCANS the walls around the holder into a persistent site map ({@link SNavMap}) as they
 * explore, and draws that map centred on the player - so it covers far more than a live scan. To
 * keep the hologram cheap, consecutive wall blocks are RUN-LENGTH merged into single line entities
 * (a 10-block wall = one stretched display, not ten). No solid backdrop - just the lines, area
 * symbols and the player dot floating in the air. It drains a battery while open and dies when
 * flat; sneak + right-click loads a fresh S-Nav Battery.
 */
public final class SNavManager implements Listener {

    private static final double DIST = 1.9;      // how far ahead of the eyes it floats
    private static final int MAX_SEGMENTS = 260; // safety cap on line entities
    private static final int REBUILD_TICKS = 10; // rescan + redraw this often (task runs /2 ticks)

    private final FacilityPlugin plugin;
    private final AreaManager areas;
    private final SNavMap map;
    private final NamespacedKey snavKey;
    private final NamespacedKey batteryKey;      // charge left, on the S-Nav item
    private final Map<UUID, Nav> open = new HashMap<>();

    public SNavManager(FacilityPlugin plugin, AreaManager areas, SNavMap map) {
        this.plugin = plugin;
        this.areas = areas;
        this.map = map;
        this.snavKey = new NamespacedKey(plugin, "snav");
        this.batteryKey = new NamespacedKey(plugin, "snav_battery");
    }

    private int batteryMax() { return Math.max(1, plugin.getConfig().getInt("snav.battery-max", 100)); }
    private int drainEvery() { return Math.max(1, plugin.getConfig().getInt("snav.battery-drain-seconds", 3)); }
    /** The single fixed Y plane the S-Nav scans/draws (a floor-plan slice, not the holder's height). */
    public int scanY() { return plugin.getConfig().getInt("snav.scan-y", 64); }
    public void setScanY(int y) { plugin.getConfig().set("snav.scan-y", y); plugin.saveConfig(); }
    /** Map display side (blocks) and how far each way it shows - both operator-tunable. */
    private float panelSize() { return (float) plugin.getConfig().getDouble("snav.panel-size", 2.4); }
    private int viewRadius() { return Math.max(8, Math.min(64, plugin.getConfig().getInt("snav.view-radius", 40))); }
    private int scanRadius() { return Math.max(4, Math.min(48, plugin.getConfig().getInt("snav.scan-radius", 16))); }

    private record Part(Display display, float u, float v, float push) { }

    private static final class Nav {
        final List<Part> statics = new ArrayList<>();
        final List<Part> walls = new ArrayList<>();
        TextDisplay meter;
        int ticks;
        int drainCounter;
    }

    // --- items --------------------------------------------------------------

    public ItemStack buildItem() {
        ItemStack item = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("S-Nav Navigator", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Right-click: project the site map", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Sneak + right-click: load a 9V battery", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(snavKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(batteryKey, PersistentDataType.INTEGER, batteryMax());
        var cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of("snav"));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSnav(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(snavKey, PersistentDataType.BYTE);
    }

    /** The S-Nav runs on the SAME 9V battery as the Lab plugin's NVGs: any item whose
     *  custom_model_data carries "lab_battery" (what Labra's NvgListener checks for). */
    private boolean isBattery(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getCustomModelDataComponent().getStrings().contains("lab_battery");
    }

    private int battery(ItemStack snav) {
        return snav.getItemMeta().getPersistentDataContainer()
            .getOrDefault(batteryKey, PersistentDataType.INTEGER, 0);
    }

    private void setBattery(Player p, ItemStack snav, int value) {
        ItemMeta meta = snav.getItemMeta();
        meta.getPersistentDataContainer().set(batteryKey, PersistentDataType.INTEGER,
            Math.max(0, Math.min(batteryMax(), value)));
        snav.setItemMeta(meta);
        p.getInventory().setItemInMainHand(snav);
    }

    // --- interaction --------------------------------------------------------

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getAction().isRightClick()) return;
        ItemStack held = event.getItem();
        if (!isSnav(held)) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        if (p.isSneaking()) { recharge(p, held); return; }
        if (p.getCooldown(held.getType()) > 0) return;
        p.setCooldown(held.getType(), 6);
        toggle(p, held);
    }

    private void recharge(Player p, ItemStack snav) {
        if (battery(snav) >= batteryMax()) {
            Msg.actionbar(p, Component.text("S-Nav battery is already full.", NamedTextColor.GRAY));
            return;
        }
        if (!consumeBattery(p)) {
            Msg.actionbar(p, Component.text("No 9V batteries in your inventory.", NamedTextColor.RED));
            return;
        }
        setBattery(p, snav, batteryMax());
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
    public void onQuit(PlayerQuitEvent event) { close(event.getPlayer().getUniqueId()); }

    public void toggle(Player player, ItemStack held) {
        if (open.containsKey(player.getUniqueId())) { close(player.getUniqueId()); return; }
        if (battery(held) <= 0) {
            Msg.actionbar(player, Component.text("S-Nav battery is dead — sneak + right-click with a 9V battery.",
                NamedTextColor.RED));
            return;
        }
        open(player);
    }

    private void open(Player player) {
        Nav nav = new Nav();
        Frame f = frame(player);
        float panel = panelSize();
        float titleV = panel / 2f + 0.12f;
        // meter / title (NO background panel any more) - VERTICAL billboard: yaws to face the
        // reader but stays upright, so it never tips when they look up/down.
        Location titleAt = f.pointOf(0, titleV, 0.02);
        nav.meter = player.getWorld().spawn(titleAt, TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));   // transparent - no block behind it
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(0.5f);
            d.setPersistent(false);
            d.setTransformation(uniform(0.5f));
        });
        nav.statics.add(new Part(nav.meter, 0, titleV, 0.02f));
        float dotSize = panel / (2 * viewRadius() + 1) * 2.4f;
        nav.statics.add(new Part(spawnDot(player, f.pointOf(0, 0, 0.03), dotSize), 0, 0, 0.03f));

        map.scan(player, scanRadius(), scanY());
        rebuild(player, nav, f);
        open.put(player.getUniqueId(), nav);
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 0.4f, 1.8f);
    }

    public void close(UUID id) {
        Nav nav = open.remove(id);
        if (nav == null) return;
        nav.statics.forEach(p -> remove(p.display()));
        nav.walls.forEach(p -> remove(p.display()));
    }

    public void closeAll() { for (UUID id : new ArrayList<>(open.keySet())) close(id); }

    private void remove(Display d) { if (d != null && !d.isDead()) d.remove(); }

    // --- per-tick -----------------------------------------------------------

    public void tick() {
        for (UUID id : new ArrayList<>(open.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            ItemStack held = p == null ? null : p.getInventory().getItemInMainHand();
            if (p == null || !p.isOnline() || !isSnav(held)) { close(id); continue; }

            Nav nav = open.get(id);
            // battery drain
            if (++nav.drainCounter >= drainEvery() * 10) {   // task fires every 2 ticks -> *10 per second-group
                nav.drainCounter = 0;
                int left = battery(held) - 1;
                setBattery(p, held, left);
                if (left <= 0) {
                    Msg.actionbar(p, Component.text("S-Nav battery depleted.", NamedTextColor.RED));
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 0.8f);
                    close(id);
                    continue;
                }
            }
            Frame f = frame(p);
            if (nav.ticks++ % REBUILD_TICKS == 0) { map.scan(p, scanRadius(), scanY()); rebuild(p, nav, f); }
            updateMeter(nav, battery(held));
            for (Part part : nav.statics) reposition(part, f);
            for (Part part : nav.walls) reposition(part, f);
        }
    }

    private void updateMeter(Nav nav, int battery) {
        if (nav.meter == null || nav.meter.isDead()) return;
        int pct = (int) Math.round(battery * 100.0 / batteryMax());
        TextColor c = pct <= 15 ? NamedTextColor.RED : pct <= 40 ? NamedTextColor.GOLD : NamedTextColor.AQUA;
        nav.meter.text(Component.text("S-NAV ", NamedTextColor.AQUA, TextDecoration.BOLD)
            .append(Component.text("⚡" + pct + "%", c)));
    }

    private void reposition(Part part, Frame f) {
        if (part.display() != null && !part.display().isDead()) {
            part.display().teleport(f.pointOf(part.u(), part.v(), part.push()));
        }
    }

    // --- map rendering (run-length merged) ----------------------------------

    private void rebuild(Player player, Nav nav, Frame f) {
        nav.walls.forEach(p -> remove(p.display()));
        nav.walls.clear();
        World w = player.getWorld();
        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();
        int view = viewRadius();
        float panel = panelSize();
        float cell = panel / (2 * view + 1);

        // pull the scanned plane into a local grid centred on the player's X/Z
        Set<Long> band = map.band(w.getName(), scanY());
        int size = 2 * view + 1;
        boolean[][] wall = new boolean[size][size];        // [ix][iz]
        for (long packed : band) {
            int dx = SNavMap.unpackX(packed) - px, dz = SNavMap.unpackZ(packed) - pz;
            if (dx < -view || dx > view || dz < -view || dz > view) continue;
            wall[dx + view][dz + view] = true;
        }
        boolean[][] used = new boolean[size][size];
        int segments = 0;

        // pass 1: horizontal runs (>= 2 long) become one stretched line
        for (int iz = 0; iz < size && segments < MAX_SEGMENTS; iz++) {
            int ix = 0;
            while (ix < size) {
                if (!wall[ix][iz]) { ix++; continue; }
                int start = ix;
                while (ix < size && wall[ix][iz]) ix++;
                int len = ix - start;
                if (len >= 2) {
                    for (int k = start; k < start + len; k++) used[k][iz] = true;
                    addSegment(nav, f, w, panel, view, cell, start, iz, len, true);
                    if (++segments >= MAX_SEGMENTS) break;
                }
            }
        }
        // pass 2: everything left (vertical runs, and lone cells) as vertical lines
        for (int ix = 0; ix < size && segments < MAX_SEGMENTS; ix++) {
            int iz = 0;
            while (iz < size) {
                if (!wall[ix][iz] || used[ix][iz]) { iz++; continue; }
                int start = iz;
                while (iz < size && wall[ix][iz] && !used[ix][iz]) iz++;
                int len = iz - start;
                addSegment(nav, f, w, panel, view, cell, start, ix, len, false);
                if (++segments >= MAX_SEGMENTS) break;
            }
        }

        // area centres as small cyan symbols
        for (AreaManager.Area a : areas.all()) {
            if (segments >= MAX_SEGMENTS) break;
            if (!a.world().equals(w.getName())) continue;
            int dx = (a.x1() + a.x2()) / 2 - px, dz = (a.z1() + a.z2()) / 2 - pz;
            if (dx < -view || dx > view || dz < -view || dz > view) continue;
            float u = (dx / (float) view) * (panel / 2f);
            float v = (-dz / (float) view) * (panel / 2f);
            nav.walls.add(new Part(spawnRect(w, f.pointOf(u, v, 0.015), Material.CYAN_CONCRETE,
                cell * 1.6f, cell * 1.6f), u, v, 0.015f));
            segments++;
        }
    }

    /** Add one merged line. {@code runStart} is the grid index where the run begins along its axis;
     *  {@code fixed} is the grid index of the perpendicular axis it sits on. */
    private void addSegment(Nav nav, Frame f, World w, float panel, int view, float cell,
                            int runStart, int fixed, int len, boolean horizontal) {
        float centreRun = (runStart + (len - 1) / 2f) - view;   // run-centre offset in cells from player
        float u, v, width, height;
        if (horizontal) {
            u = (centreRun / view) * (panel / 2f);
            v = (-(fixed - view) / (float) view) * (panel / 2f);
            width = len * cell; height = cell;
        } else {
            u = ((fixed - view) / (float) view) * (panel / 2f);
            v = (-centreRun / view) * (panel / 2f);
            width = cell; height = len * cell;
        }
        nav.walls.add(new Part(spawnRect(w, f.pointOf(u, v, 0.01), Material.LIGHT_GRAY_CONCRETE,
            width, height), u, v, 0.01f));
    }

    // --- geometry + spawn helpers ------------------------------------------

    private static final class Frame {
        final Location origin; final Vector right; final Vector up;
        Frame(Location origin, Vector right, Vector up) { this.origin = origin; this.right = right; this.up = up; }
        Location pointOf(double u, double v, double push) {
            Vector normal = right.clone().crossProduct(up).normalize();
            return origin.clone()
                .add(right.clone().multiply(u)).add(up.clone().multiply(v)).add(normal.multiply(push));
        }
    }

    private Frame frame(Player p) {
        Vector dir = p.getEyeLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        Location origin = p.getEyeLocation().add(dir.clone().multiply(DIST));
        origin.setPitch(0); origin.setYaw(0);
        Vector up = new Vector(0, 1, 0);
        Vector right = dir.clone().crossProduct(up).normalize();
        return new Frame(origin, right, up);
    }

    private ItemDisplay spawnRect(World w, Location at, Material mat, float width, float height) {
        return w.spawn(at, ItemDisplay.class, disp -> {
            disp.setItemStack(new ItemStack(mat));
            // VERTICAL billboard: the cell yaws to face the reader but stays upright, so it never
            // tips/rotates when the player looks up or down (the reported spin).
            disp.setBillboard(Display.Billboard.VERTICAL);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setViewRange(0.5f);
            disp.setTransformation(new Transformation(
                new Vector3f(-width / 2f, -height / 2f, 0f), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(width, height, 0.02f), new AxisAngle4f(0, 0, 0, 1)));
        });
    }

    private ItemDisplay spawnDot(Player p, Location at, float s) {
        return p.getWorld().spawn(at, ItemDisplay.class, disp -> {
            disp.setItemStack(new ItemStack(Material.REDSTONE_BLOCK));
            disp.setBillboard(Display.Billboard.VERTICAL);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setViewRange(0.5f);
            disp.setTransformation(new Transformation(
                new Vector3f(-s / 2f, -s / 2f, 0f), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(s, s, s), new AxisAngle4f(0, 0, 0, 1)));
        });
    }

    private Transformation uniform(float s) {
        return new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(s, s, s), new AxisAngle4f(0, 0, 0, 1));
    }
}
