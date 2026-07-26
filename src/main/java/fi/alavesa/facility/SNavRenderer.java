package fi.alavesa.facility;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Draws the S-Nav onto a real Minecraft map (128x128, 1 pixel = 1 block) instead of block-display
 * holograms. Contextual, so every holder gets their own view: it re-centres on the holder and
 * redraws the moment they walk near the edge of the current area (no battery change on recentre),
 * and it plants a live cursor for the holder and every nearby player. When the S-Nav's battery is
 * flat it renders a dead grey screen. All wall detection is per-pixel from the world at the fixed
 * scan plane, so it picks up walls the hologram missed.
 */
public final class SNavRenderer extends MapRenderer {

    private static final int HALF = 64;              // 128px map, centre pixel = 64
    private static final int RECENTER_MARGIN = 10;   // recentre when this close to the edge

    private static final Color WALL = new Color(214, 214, 220);
    private static final Color FLOOR = new Color(46, 46, 52);
    private static final Color VOID = new Color(16, 16, 20);
    private static final Color DEAD = new Color(0, 0, 0);   // battery flat -> completely dark screen

    private final FacilityPlugin plugin;
    private final SNavManager snav;
    private final Map<UUID, int[]> center = new HashMap<>();
    private final Map<UUID, Long> lastDraw = new HashMap<>();   // wall-clock of last map redraw
    private final Map<UUID, Boolean> wasDead = new HashMap<>();

    /** How rarely the map picture itself refreshes (recentres always redraw). Kept slow so the
     *  map isn't visibly re-rendering under the player's nose every few seconds. */
    private long refreshMillis() {
        return Math.max(2, plugin.getConfig().getInt("snav.refresh-seconds", 30)) * 1000L;
    }

    public SNavRenderer(FacilityPlugin plugin, SNavManager snav) {
        super(true);   // contextual: per-player canvas + render call
        this.plugin = plugin;
        this.snav = snav;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        ItemStack held = snav.heldSnav(player);   // main OR off hand - so it works in the offhand
        if (held == null) return;                 // (and never freezes because a hand check failed)
        UUID id = player.getUniqueId();

        if (snav.battery(held) <= 0) {
            if (!Boolean.TRUE.equals(wasDead.get(id))) {
                for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) canvas.setPixelColor(x, y, DEAD);
                wasDead.put(id, true);
            }
            canvas.setCursors(new MapCursorCollection());
            return;
        }
        wasDead.put(id, false);

        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();
        int[] c = center.get(id);
        long now = System.currentTimeMillis();
        boolean redraw = false;
        if (c == null || Math.abs(px - c[0]) > HALF - RECENTER_MARGIN
            || Math.abs(pz - c[1]) > HALF - RECENTER_MARGIN) {
            c = new int[]{px, pz};      // stepped out of bounds -> recentre on the player
            center.put(id, c);
            redraw = true;
        } else if (now - lastDraw.getOrDefault(id, 0L) >= refreshMillis()) {
            redraw = true;              // slow periodic refresh to catch door/build changes
        }
        if (redraw) { drawMap(canvas, player.getWorld(), c); lastDraw.put(id, now); }

        canvas.setCursors(cursors(player, c));
        drawBattery(canvas, snav.battery(held), snav.batteryMax());
    }

    /** Battery readout: five bars in the TOP-RIGHT corner that empty one at a time as it drains. */
    private void drawBattery(MapCanvas canvas, int battery, int max) {
        float frac = Math.max(0f, Math.min(1f, battery / (float) Math.max(1, max)));
        int lit = (int) Math.ceil(frac * 5f);   // 0..5 bars lit
        Color fill = frac <= 0.15f ? new Color(210, 45, 45)
            : frac <= 0.40f ? new Color(220, 180, 45) : new Color(70, 200, 95);
        Color empty = new Color(40, 40, 46);
        Color frame = new Color(12, 12, 16);
        int bw = 4, gap = 1, h = 9, top = 3;
        int startX = 128 - 3 - (5 * bw + 4 * gap);   // right-aligned, ~3px margin
        for (int i = 0; i < 5; i++) {
            int x0 = startX + i * (bw + gap);
            Color col = i < lit ? fill : empty;
            for (int x = x0 - 1; x <= x0 + bw; x++)      // thin dark frame around each bar
                for (int y = top - 1; y <= top + h; y++)
                    canvas.setPixelColor(x, y, frame);
            for (int x = x0; x < x0 + bw; x++)
                for (int y = top; y < top + h; y++)
                    canvas.setPixelColor(x, y, col);
        }
    }

    private void drawMap(MapCanvas canvas, World w, int[] c) {
        int y = snav.scanY();
        for (int pyl = 0; pyl < 128; pyl++) {
            int wz = c[1] - HALF + pyl;
            for (int pxl = 0; pxl < 128; pxl++) {
                int wx = c[0] - HALF + pxl;
                Color col;
                if (!w.isChunkLoaded(wx >> 4, wz >> 4)) col = VOID;
                else col = isWall(w, wx, y, wz) ? WALL : FLOOR;
                canvas.setPixelColor(pxl, pyl, col);
            }
        }
    }

    /** Solid at the plane or one block above (isSolid, so glass/stairs/slabs/walls/fences count). */
    private boolean isWall(World w, int x, int y, int z) {
        return w.getBlockAt(x, y, z).getType().isSolid()
            || w.getBlockAt(x, y + 1, z).getType().isSolid();
    }

    private MapCursorCollection cursors(Player holder, int[] c) {
        MapCursorCollection cc = new MapCursorCollection();
        addCursor(cc, holder, c, MapCursor.Type.PLAYER);
        if (plugin.getConfig().getBoolean("snav.show-players", true)) {
            for (Player p : holder.getWorld().getPlayers()) {
                if (p.equals(holder)) continue;
                addCursor(cc, p, c, MapCursor.Type.RED_MARKER);
            }
        }
        return cc;
    }

    private void addCursor(MapCursorCollection cc, Player p, int[] c, MapCursor.Type type) {
        int dx = p.getLocation().getBlockX() - c[0];
        int dz = p.getLocation().getBlockZ() - c[1];
        if (Math.abs(dx) > HALF || Math.abs(dz) > HALF) return;   // outside this map area
        byte cx = (byte) Math.max(-128, Math.min(127, dx * 2));
        byte cy = (byte) Math.max(-128, Math.min(127, dz * 2));
        byte dir = (byte) (Math.round(p.getLocation().getYaw() / 22.5f) & 0x0F);
        cc.addCursor(new MapCursor(cx, cy, dir, type, true));
    }

    /** Drop cached per-player state (called when a player leaves). */
    public void forget(UUID id) {
        center.remove(id);
        lastDraw.remove(id);
        wasDead.remove(id);
    }
}
