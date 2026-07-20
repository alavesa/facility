package fi.alavesa.facility;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Named map areas: cuboid regions defined with pos1/pos2, persisted to areas.yml.
 * Each area can apply potion effects to anyone inside and/or slowly infect them
 * with SCP-008 (by feeding Labra's lab.z008 scoreboard). AreaTask drives the
 * per-tick effects; the area a player stands in is also their "last area" (shown
 * in stats and the tab list) and a handy moderation/troubleshooting readout.
 *
 * Areas are stored as: name -> {world, x1,y1,z1, x2,y2,z2, effects[], scp008}.
 * effects are "TYPE:amplifier" (e.g. "SLOWNESS:1", "DARKNESS:0").
 */
public final class AreaManager {

    public record Area(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2,
                       List<String> effects, boolean scp008) {
        boolean contains(Location l) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }
    }

    private final File file;
    private final YamlConfiguration cfg;
    private final Map<String, Area> areas = new HashMap<>();
    private final Map<UUID, int[]> pos1 = new HashMap<>();
    private final Map<UUID, int[]> pos2 = new HashMap<>();

    public AreaManager(FacilityPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "areas.yml");
        this.cfg = YamlConfiguration.loadConfiguration(file);
        load();
    }

    public void load() {
        areas.clear();
        var sec = cfg.getConfigurationSection("areas");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            String p = "areas." + name + ".";
            areas.put(name.toLowerCase(Locale.ROOT), new Area(name,
                cfg.getString(p + "world", "world"),
                cfg.getInt(p + "x1"), cfg.getInt(p + "y1"), cfg.getInt(p + "z1"),
                cfg.getInt(p + "x2"), cfg.getInt(p + "y2"), cfg.getInt(p + "z2"),
                cfg.getStringList(p + "effects"), cfg.getBoolean(p + "scp008", false)));
        }
    }

    private void save() {
        try { cfg.save(file); } catch (IOException ignored) { }
    }

    // --- selection ----------------------------------------------------------

    public void setPos1(Player p) { pos1.put(p.getUniqueId(), coords(p)); }
    public void setPos2(Player p) { pos2.put(p.getUniqueId(), coords(p)); }

    private int[] coords(Player p) {
        Location l = p.getLocation();
        return new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()};
    }

    /** Create/replace an area from the caller's pos1+pos2. Returns null on success, else why not. */
    public String create(Player p, String name) {
        int[] a = pos1.get(p.getUniqueId()), b = pos2.get(p.getUniqueId());
        if (a == null || b == null) return "Set both pos1 and pos2 first.";
        String key = name.toLowerCase(Locale.ROOT);
        String base = "areas." + key + ".";
        cfg.set(base + "world", p.getWorld().getName());
        cfg.set(base + "x1", Math.min(a[0], b[0])); cfg.set(base + "x2", Math.max(a[0], b[0]));
        cfg.set(base + "y1", Math.min(a[1], b[1])); cfg.set(base + "y2", Math.max(a[1], b[1]));
        cfg.set(base + "z1", Math.min(a[2], b[2])); cfg.set(base + "z2", Math.max(a[2], b[2]));
        if (!cfg.isSet(base + "effects")) cfg.set(base + "effects", new ArrayList<String>());
        if (!cfg.isSet(base + "scp008")) cfg.set(base + "scp008", false);
        save();
        load();
        return null;
    }

    public boolean remove(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!areas.containsKey(key)) return false;
        cfg.set("areas." + key, null);
        save();
        load();
        return true;
    }

    /** Add a potion effect ("TYPE:amplifier") to an area. Returns null on success. */
    public String addEffect(String name, String effect) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!areas.containsKey(key)) return "No area named '" + name + "'.";
        List<String> list = cfg.getStringList("areas." + key + ".effects");
        list.add(effect);
        cfg.set("areas." + key + ".effects", list);
        save();
        load();
        return null;
    }

    public void clearEffects(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        cfg.set("areas." + key + ".effects", new ArrayList<String>());
        save();
        load();
    }

    public boolean setScp008(String name, boolean on) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!areas.containsKey(key)) return false;
        cfg.set("areas." + key + ".scp008", on);
        save();
        load();
        return true;
    }

    // --- lookup -------------------------------------------------------------

    public java.util.Collection<Area> all() { return areas.values(); }

    /** The area a location sits in (first match), or null. */
    public Area areaAt(Location l) {
        for (Area a : areas.values()) if (a.contains(l)) return a;
        return null;
    }
}
