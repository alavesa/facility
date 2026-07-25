package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
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
import java.util.UUID;

/**
 * The S-Nav (Site Navigator): a handheld tool that projects a small floating minimap in front of
 * the holder - like the ID-card hologram. The map is CENTRED on the player and drawn from the
 * ACTUAL WALLS around them (a live top-down scan of the blocks in radius), with a bright dot for
 * the player in the middle. North-up; right-click toggles; it follows the player and folds away
 * when they stop holding it or log off.
 */
public final class SNavManager implements Listener {

    private static final float PANEL = 1.15f;    // square panel side, in blocks (kept small)
    private static final double DIST = 1.5;      // how far ahead of the eyes it floats
    private static final int RADIUS = 8;         // blocks scanned each way around the player
    private static final int MAX_CELLS = 180;    // safety cap on wall cells
    private static final int REBUILD_TICKS = 10; // rescan the walls this often (~0.5s)

    private final FacilityPlugin plugin;
    private final NamespacedKey snavKey;
    private final Map<UUID, Nav> open = new HashMap<>();

    public SNavManager(FacilityPlugin plugin, AreaManager areas) {
        this.plugin = plugin;
        this.snavKey = new NamespacedKey(plugin, "snav");
    }

    /** A display plus its fixed panel-local position (u right, v up, push out along the normal). */
    private record Part(Display display, float u, float v, float push) { }

    private static final class Nav {
        final List<Part> statics = new ArrayList<>();   // background, title, centre dot
        final List<Part> walls = new ArrayList<>();     // rebuilt on a timer
        int ticks;
    }

    // --- the item -----------------------------------------------------------

    public ItemStack buildItem() {
        ItemStack item = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("S-Nav Navigator", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Right-click: project the site map", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(snavKey, PersistentDataType.BYTE, (byte) 1);
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

    // --- open / close -------------------------------------------------------

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getAction().isRightClick()) return;
        if (!isSnav(event.getItem())) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        if (p.getCooldown(event.getItem().getType()) > 0) return;
        p.setCooldown(event.getItem().getType(), 6);
        toggle(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { close(event.getPlayer().getUniqueId()); }

    public void toggle(Player player) {
        if (open.containsKey(player.getUniqueId())) { close(player.getUniqueId()); return; }
        open(player);
    }

    private void open(Player player) {
        Nav nav = new Nav();
        Frame f = frame(player);
        // background panel (dark)
        nav.statics.add(new Part(spawnRect(player.getWorld(), f.pointOf(0, 0, 0.0),
            Material.BLACK_CONCRETE, PANEL, PANEL), 0, 0, 0.0f));
        // title strip just above the map
        Location titleAt = f.pointOf(0, PANEL / 2f + 0.1f, 0.02);
        TextDisplay title = player.getWorld().spawn(titleAt, TextDisplay.class, d -> {
            d.text(Component.text("S-NAV", NamedTextColor.AQUA, TextDecoration.BOLD));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setPersistent(false);
            d.setViewRange(0.5f);
            d.setTransformation(uniform(0.4f));
        });
        nav.statics.add(new Part(title, 0, PANEL / 2f + 0.1f, 0.02f));
        // the player's dot, dead centre (the map is player-centred)
        nav.statics.add(new Part(spawnDot(player, f.pointOf(0, 0, 0.03)), 0, 0, 0.03f));

        rebuildWalls(player, nav, f);
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

    // --- per-tick follow + wall refresh -------------------------------------

    public void tick() {
        for (UUID id : new ArrayList<>(open.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline() || !isSnav(p.getInventory().getItemInMainHand())) {
                close(id);
                continue;
            }
            Nav nav = open.get(id);
            Frame f = frame(p);
            if (nav.ticks++ % REBUILD_TICKS == 0) rebuildWalls(p, nav, f);
            for (Part part : nav.statics) reposition(part, f);
            for (Part part : nav.walls) reposition(part, f);
        }
    }

    private void reposition(Part part, Frame f) {
        if (part.display() != null && !part.display().isDead()) {
            part.display().teleport(f.pointOf(part.u(), part.v(), part.push()));
        }
    }

    /** Rescan the walls around the player and rebuild the wall cells (player-centred, north-up). */
    private void rebuildWalls(Player player, Nav nav, Frame f) {
        nav.walls.forEach(p -> remove(p.display()));
        nav.walls.clear();
        World w = player.getWorld();
        int px = player.getLocation().getBlockX();
        int py = player.getLocation().getBlockY();
        int pz = player.getLocation().getBlockZ();
        float cell = PANEL / (2 * RADIUS + 1);
        int count = 0;
        for (int dz = -RADIUS; dz <= RADIUS && count < MAX_CELLS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS && count < MAX_CELLS; dx++) {
                if (dx == 0 && dz == 0) continue;                 // centre = the player dot
                if (!isWall(w, px + dx, py, pz + dz)) continue;
                float u = (dx / (float) RADIUS) * (PANEL / 2f);
                float v = (-dz / (float) RADIUS) * (PANEL / 2f); // north (-Z) is up
                Location at = f.pointOf(u, v, 0.01);
                nav.walls.add(new Part(spawnRect(w, at, Material.LIGHT_GRAY_CONCRETE, cell, cell), u, v, 0.01f));
                count++;
            }
        }
    }

    /** A column counts as a wall if it's solid at head height (what boxes you in a corridor). */
    private boolean isWall(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y + 1, z);
        return b.getType().isOccluding();
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
        Vector right = dir.clone().crossProduct(up).normalize();   // viewer's screen-right
        return new Frame(origin, right, up);
    }

    private ItemDisplay spawnRect(World w, Location at, Material mat, float width, float height) {
        return w.spawn(at, ItemDisplay.class, disp -> {
            disp.setItemStack(new ItemStack(mat));
            disp.setBillboard(Display.Billboard.CENTER);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setViewRange(0.5f);
            disp.setTransformation(new Transformation(
                new Vector3f(-width / 2f, -height / 2f, 0f), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(width, height, 0.02f), new AxisAngle4f(0, 0, 0, 1)));
        });
    }

    private ItemDisplay spawnDot(Player p, Location at) {
        float s = PANEL / (2 * RADIUS + 1) * 1.3f;
        return p.getWorld().spawn(at, ItemDisplay.class, disp -> {
            disp.setItemStack(new ItemStack(Material.REDSTONE_BLOCK));
            disp.setBillboard(Display.Billboard.CENTER);
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
