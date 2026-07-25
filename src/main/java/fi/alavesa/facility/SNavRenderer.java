package fi.alavesa.facility;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

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
    private static final int REDRAW_EVERY = 200;     // periodic refresh (renders) to catch changes

    private static final Color WALL = new Color(214, 214, 220);
    private static final Color FLOOR = new Color(46, 46, 52);
    private static final Color VOID = new Color(16, 16, 20);
    private static final Color DEAD = new Color(0, 0, 0);   // battery flat -> completely dark screen

    private final FacilityPlugin plugin;
    private final SNavManager snav;
    private final Map<UUID, int[]> center = new HashMap<>();
    private final Map<UUID, Integer> sinceDraw = new HashMap<>();
    private final Map<UUID, Boolean> wasDead = new HashMap<>();

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
        int since = sinceDraw.getOrDefault(id, REDRAW_EVERY) + 1;
        boolean redraw = false;
        if (c == null || Math.abs(px - c[0]) > HALF - RECENTER_MARGIN
            || Math.abs(pz - c[1]) > HALF - RECENTER_MARGIN) {
            c = new int[]{px, pz};      // stepped out of bounds -> recentre on the player
            center.put(id, c);
            redraw = true;
        } else if (since >= REDRAW_EVERY) {
            redraw = true;
        }
        if (redraw) { drawMap(canvas, player.getWorld(), c); since = 0; }
        sinceDraw.put(id, since);

        canvas.setCursors(cursors(player, c));
        drawBattery(canvas, snav.battery(held), snav.batteryMax());
    }

    /** Battery readout across the top of the map: a coloured bar + the percentage. */
    private void drawBattery(MapCanvas canvas, int battery, int max) {
        int pct = Math.round(battery * 100f / Math.max(1, max));
        int filled = Math.round(124 * Math.max(0, Math.min(1f, battery / (float) max)));
        Color bar = pct <= 15 ? new Color(210, 45, 45)
            : pct <= 40 ? new Color(220, 180, 45) : new Color(70, 200, 95);
        Color empty = new Color(38, 38, 44);
        for (int x = 2; x <= 125; x++) {
            Color col = (x - 2) < filled ? bar : empty;
            canvas.setPixelColor(x, 1, col);
            canvas.setPixelColor(x, 2, col);
        }
        String code = pct <= 15 ? "§c" : pct <= 40 ? "§e" : "§a";
        canvas.drawText(4, 4, MinecraftFont.Font, code + "BAT " + pct + "%");
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
        sinceDraw.remove(id);
        wasDead.remove(id);
    }
}
