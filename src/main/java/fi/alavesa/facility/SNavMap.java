package fi.alavesa.facility;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The scanned site map behind the S-Nav. Walls the players SCAN with the navigator are remembered
 * here (per world, split into vertical bands so different floors don't smear together), so the
 * S-Nav shows far more than it could live-scan every tick. Cells are packed (x,z) longs; the map
 * persists to snav-map.yml and simply accumulates as the site is explored.
 */
public final class SNavMap {

    private static final int Y_BAND = 6;              // blocks per floor band
    private static final int MAX_PER_BUCKET = 60000;  // stop a runaway world from bloating the file

    private final FacilityPlugin plugin;
    private final File file;
    private final YamlConfiguration yaml;
    private final Map<String, Set<Long>> cells = new HashMap<>();   // "world#band" -> packed (x,z)
    private boolean dirty;

    public SNavMap(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "snav-map.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
        load();
    }

    private static long pack(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    static int unpackX(long p) { return (int) (p >> 32); }
    static int unpackZ(long p) { return (int) p; }
    private static String key(String world, int y) { return world + "#" + Math.floorDiv(y, Y_BAND); }

    // --- query --------------------------------------------------------------

    /** The wall cells for the band the given Y sits in (empty set if none scanned yet). */
    public Set<Long> band(String world, int y) {
        return cells.getOrDefault(key(world, y), java.util.Collections.emptySet());
    }

    // --- scanning -----------------------------------------------------------

    /** Scan a square radius around the player, but sampling a SINGLE FIXED 2D plane at {@code planeY}
     *  (not the player's own height) - so the map is always the same floor-plan slice however high
     *  or low the holder is standing. Returns the number of new wall cells added. */
    public int scan(Player player, int radius, int planeY) {
        World w = player.getWorld();
        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();
        Set<Long> bucket = cells.computeIfAbsent(key(w.getName(), planeY), k -> new HashSet<>());
        int added = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (bucket.size() >= MAX_PER_BUCKET) break;
                if (!isWall(w, px + dx, planeY, pz + dz)) continue;
                if (bucket.add(pack(px + dx, pz + dz))) added++;
            }
        }
        if (added > 0) dirty = true;
        return added;
    }

    /** A column is a wall if it's solid at the plane OR one block above it. Uses isSolid() (not the
     *  stricter isOccluding), so glass, stairs, slabs, walls and fences all count - which is what
     *  was making thin/partial walls go undetected. */
    private boolean isWall(World w, int x, int y, int z) {
        return w.getBlockAt(x, y, z).getType().isSolid()
            || w.getBlockAt(x, y + 1, z).getType().isSolid();
    }

    /** Forget the scanned slice for a given plane. */
    public int clearPlane(String world, int planeY) {
        Set<Long> removed = cells.remove(key(world, planeY));
        if (removed != null && !removed.isEmpty()) { dirty = true; return removed.size(); }
        return 0;
    }

    // --- persistence --------------------------------------------------------

    private void load() {
        var sec = yaml.getConfigurationSection("cells");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            Set<Long> set = new HashSet<>();
            for (String s : yaml.getStringList("cells." + k)) {
                int c = s.indexOf(',');
                if (c < 0) continue;
                try { set.add(pack(Integer.parseInt(s.substring(0, c)), Integer.parseInt(s.substring(c + 1)))); }
                catch (NumberFormatException ignored) { }
            }
            // config keys can't contain '.', so bands are stored with '#'->'_' escaped
            cells.put(k.replace('_', '#'), set);
        }
    }

    public void saveIfDirty() {
        if (!dirty) return;
        yaml.set("cells", null);
        for (var e : cells.entrySet()) {
            List<String> list = new java.util.ArrayList<>(e.getValue().size());
            for (long p : e.getValue()) list.add(unpackX(p) + "," + unpackZ(p));
            yaml.set("cells." + e.getKey().replace('#', '_'), list);
        }
        try { yaml.save(file); dirty = false; }
        catch (IOException ex) { plugin.getLogger().severe("Could not save snav-map.yml: " + ex.getMessage()); }
    }
}
