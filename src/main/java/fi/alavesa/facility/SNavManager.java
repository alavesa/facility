package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
 * The S-Nav (Site Navigator): a handheld tool that projects a floating top-down map in front of
 * the holder - just like the ID-card hologram, but instead of a card it draws the site's actual
 * 2D layout (every named Facility area as a rectangle, scaled into the panel) with a bright dot
 * for where the holder is standing. Right-click toggles it; it follows the player as a north-up
 * minimap and folds away when they stop holding it or log off.
 *
 * All parts are display entities billboarded to face the viewer, repositioned each tick against
 * a base a little ahead of the eyes. The world->panel mapping uses the bounding box of all areas
 * in the player's world (or a box around the player if none are defined), so it self-calibrates.
 */
public final class SNavManager implements Listener {

    private static final float PANEL_W = 2.2f;      // panel width in blocks
    private static final float PANEL_H = 1.6f;      // panel height in blocks
    private static final double DIST = 1.4;         // how far ahead of the eyes it floats
    private static final int MAX_AREAS = 24;        // cap the rectangles drawn

    private final FacilityPlugin plugin;
    private final AreaManager areas;
    private final NamespacedKey snavKey;
    private final Map<UUID, List<Display>> open = new HashMap<>();

    public SNavManager(FacilityPlugin plugin, AreaManager areas) {
        this.plugin = plugin;
        this.areas = areas;
        this.snavKey = new NamespacedKey(plugin, "snav");
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
        cmd.setStrings(List.of("snav"));   // pack may skin it; falls back to the compass otherwise
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
        p.setCooldown(event.getItem().getType(), 6);   // debounce the double RIGHT_CLICK_AIR/BLOCK
        toggle(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        close(event.getPlayer().getUniqueId());
    }

    public void toggle(Player player) {
        if (open.containsKey(player.getUniqueId())) { close(player.getUniqueId()); return; }
        open(player);
    }

    private void open(Player player) {
        List<Display> parts = new ArrayList<>();
        double[] bounds = mapBounds(player);   // {minX, minZ, maxX, maxZ}
        Frame f = frame(player);

        // background panel
        parts.add(spawnRect(player, f, 0, 0, PANEL_W, PANEL_H, Material.BLACK_CONCRETE, 0.0f));
        // title strip
        Location titleAt = f.pointOf(0, PANEL_H / 2f + 0.12f, 0.02);
        TextDisplay title = player.getWorld().spawn(titleAt, TextDisplay.class, d -> {
            d.text(Component.text("S-NAV // SITE-19", NamedTextColor.AQUA, TextDecoration.BOLD));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setPersistent(false);
            d.setTransformation(scale(0.5f));
        });
        parts.add(title);

        // one rectangle per area, coloured by name
        int drawn = 0;
        for (AreaManager.Area a : areas.all()) {
            if (!a.world().equals(player.getWorld().getName())) continue;
            if (drawn++ >= MAX_AREAS) break;
            double cx = (a.x1() + a.x2()) / 2.0, cz = (a.z1() + a.z2()) / 2.0;
            float u = mapU(cx, bounds), v = mapV(cz, bounds);
            float w = (float) Math.max(0.06, (Math.abs(a.x2() - a.x1()) / span(bounds[0], bounds[2])) * PANEL_W);
            float h = (float) Math.max(0.06, (Math.abs(a.z2() - a.z1()) / span(bounds[1], bounds[3])) * PANEL_H);
            parts.add(spawnRect(player, f, u, v, w, h, areaMaterial(a.name()), 0.01f));
        }

        // the player's dot, on top
        float du = mapU(player.getX(), bounds), dv = mapV(player.getZ(), bounds);
        parts.add(spawnDot(player, f, du, dv));

        open.put(player.getUniqueId(), parts);
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 0.4f, 1.8f);
    }

    public void close(UUID id) {
        List<Display> parts = open.remove(id);
        if (parts != null) parts.forEach(d -> { if (d != null && !d.isDead()) d.remove(); });
    }

    public void closeAll() {
        for (UUID id : new ArrayList<>(open.keySet())) close(id);
    }

    // --- per-tick follow + marker update ------------------------------------

    public void tick() {
        for (UUID id : new ArrayList<>(open.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline() || !isSnav(p.getInventory().getItemInMainHand())) {
                close(id);
                continue;
            }
            List<Display> parts = open.get(id);
            if (parts == null || parts.isEmpty()) { close(id); continue; }
            double[] bounds = mapBounds(p);
            Frame f = frame(p);
            // parts order: [0]=background, [1]=title, [2..n-2]=areas, [n-1]=dot.
            // Reposition background + title + dot precisely; areas keep their world spots but we
            // re-anchor the whole cluster by teleporting each to its recomputed base offset.
            reposition(parts.get(0), f.pointOf(0, 0, 0.0));
            reposition(parts.get(1), f.pointOf(0, PANEL_H / 2f + 0.12f, 0.02));
            // areas: recompute from stored area list again (cheap, and keeps them aligned)
            int idx = 2;
            for (AreaManager.Area a : areas.all()) {
                if (!a.world().equals(p.getWorld().getName())) continue;
                if (idx >= parts.size() - 1) break;
                double cx = (a.x1() + a.x2()) / 2.0, cz = (a.z1() + a.z2()) / 2.0;
                reposition(parts.get(idx++), f.pointOf(mapU(cx, bounds), mapV(cz, bounds), 0.01));
            }
            // the dot is always the last part
            float du = mapU(p.getX(), bounds), dv = mapV(p.getZ(), bounds);
            reposition(parts.get(parts.size() - 1), f.pointOf(du, dv, 0.02));
        }
    }

    private void reposition(Display d, Location at) {
        if (d != null && !d.isDead()) d.teleport(at);
    }

    // --- geometry helpers ---------------------------------------------------

    /** A viewer-anchored panel frame: an origin ahead of the eyes and right/up axes on it. */
    private static final class Frame {
        final Location origin; final Vector right; final Vector up;
        Frame(Location origin, Vector right, Vector up) { this.origin = origin; this.right = right; this.up = up; }
        /** World location of a panel point (u right, v up, push out along the normal by d). */
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

    private ItemDisplay spawnRect(Player p, Frame f, float u, float v, float w, float h, Material mat, float push) {
        Location at = f.pointOf(u, v, push);
        return p.getWorld().spawn(at, ItemDisplay.class, disp -> {
            disp.setItemStack(new ItemStack(mat));
            disp.setBillboard(Display.Billboard.CENTER);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            disp.setTransformation(new Transformation(
                new Vector3f(-w / 2f, -h / 2f, 0f), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(w, h, 0.03f), new AxisAngle4f(0, 0, 0, 1)));
        });
    }

    private ItemDisplay spawnDot(Player p, Frame f, float u, float v) {
        Location at = f.pointOf(u, v, 0.02);
        return p.getWorld().spawn(at, ItemDisplay.class, disp -> {
            disp.setItemStack(new ItemStack(Material.REDSTONE_BLOCK));
            disp.setBillboard(Display.Billboard.CENTER);
            disp.setBrightness(new Display.Brightness(15, 15));
            disp.setPersistent(false);
            float s = 0.12f;
            disp.setTransformation(new Transformation(
                new Vector3f(-s / 2f, -s / 2f, 0f), new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(s, s, s), new AxisAngle4f(0, 0, 0, 1)));
        });
    }

    private Transformation scale(float s) {
        return new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1),
            new Vector3f(s, s, s), new AxisAngle4f(0, 0, 0, 1));
    }

    /** {minX, minZ, maxX, maxZ} of all areas in the player's world, or a box around the player. */
    private double[] mapBounds(Player p) {
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        boolean any = false;
        for (AreaManager.Area a : areas.all()) {
            if (!a.world().equals(p.getWorld().getName())) continue;
            any = true;
            minX = Math.min(minX, Math.min(a.x1(), a.x2()));
            maxX = Math.max(maxX, Math.max(a.x1(), a.x2()));
            minZ = Math.min(minZ, Math.min(a.z1(), a.z2()));
            maxZ = Math.max(maxZ, Math.max(a.z1(), a.z2()));
        }
        if (!any) {
            double r = 64;
            return new double[]{p.getX() - r, p.getZ() - r, p.getX() + r, p.getZ() + r};
        }
        // pad a little so edge areas / the player aren't flush to the border
        double padX = (maxX - minX) * 0.08 + 2, padZ = (maxZ - minZ) * 0.08 + 2;
        return new double[]{minX - padX, minZ - padZ, maxX + padX, maxZ + padZ};
    }

    private double span(double lo, double hi) { return Math.max(1.0, hi - lo); }

    /** World X -> panel right coordinate (east is right), centred on 0. */
    private float mapU(double worldX, double[] b) {
        double t = (worldX - b[0]) / span(b[0], b[2]);
        return (float) ((clamp01(t) - 0.5) * PANEL_W);
    }

    /** World Z -> panel up coordinate (north / smaller Z is up), centred on 0. */
    private float mapV(double worldZ, double[] b) {
        double t = (worldZ - b[1]) / span(b[1], b[3]);
        return (float) ((0.5 - clamp01(t)) * PANEL_H);
    }

    private double clamp01(double t) { return Math.max(0, Math.min(1, t)); }

    private static final Material[] PALETTE = {
        Material.CYAN_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.LIME_CONCRETE,
        Material.YELLOW_CONCRETE, Material.ORANGE_CONCRETE, Material.MAGENTA_CONCRETE,
        Material.PURPLE_CONCRETE, Material.PINK_CONCRETE, Material.GREEN_CONCRETE,
        Material.BLUE_CONCRETE, Material.RED_CONCRETE, Material.WHITE_CONCRETE
    };

    private Material areaMaterial(String name) {
        return PALETTE[Math.floorMod(name.toLowerCase().hashCode(), PALETTE.length)];
    }
}
