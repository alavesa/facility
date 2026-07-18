package fi.alavesa.facility;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Teams live in config.yml and can grow, so this reads them straight from
 * config (always in sync with what's on disk) and writes back through the
 * same config when an admin adds/removes a team or grants access.
 *
 * LuckPerms rank-granting is done by dispatching the console command
 * `lp user <player> parent set <rank>`. That's the deliberate robustness
 * choice: no LuckPerms maven dependency to complicate the build, and it works
 * whether LuckPerms exposes its API or not. If LuckPerms is absent the command
 * simply no-ops (and we log it), which is fine on a dev box.
 */
public final class TeamManager {

    private final FacilityPlugin plugin;
    private final Map<String, Team> teams = new LinkedHashMap<>();

    public TeamManager(FacilityPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)read every team from config.yml, then push each team's rank/prefix/
     *  permissions into LuckPerms so the groups match the config. */
    public void load() {
        teams.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("teams");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(id);
                if (sec == null) continue;
                String key = id.toLowerCase(Locale.ROOT);
                String display = sec.getString("display", "&f" + id);
                boolean priv = "private".equalsIgnoreCase(sec.getString("type", "public"));
                String rank = sec.getString("rank", key);
                Material icon = Material.matchMaterial(sec.getString("icon", "PAPER"));
                if (icon == null) icon = Material.PAPER;
                String prefix = sec.getString("prefix", "");
                List<String> perms = sec.getStringList("permissions");
                teams.put(key, new Team(key, display, priv, rank, icon, prefix, perms));
            }
        }
        syncGroups();
    }

    /**
     * Make every team's LuckPerms GROUP match its config: create the group,
     * have it inherit `default` (so putting a user into it doesn't strip base
     * perms), set the chat prefix, and grant the listed permissions. Idempotent,
     * so editing config.yml + /facility reload re-applies. No-op without LuckPerms.
     */
    private void syncGroups() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return;
        for (Team team : teams.values()) {
            lp("creategroup " + team.rank());
            lp("group " + team.rank() + " parent add default");
            if (team.hasPrefix()) {
                lp("group " + team.rank() + " meta setprefix 100 \"" + team.prefix() + "\"");
            }
            for (String perm : team.permissions()) {
                if (perm != null && !perm.isBlank()) {
                    lp("group " + team.rank() + " permission set " + perm.trim() + " true");
                }
            }
        }
    }

    private void lp(String args) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp " + args);
    }

    public java.util.Collection<Team> all() {
        return teams.values();
    }

    public Team get(String id) {
        return id == null ? null : teams.get(id.toLowerCase(Locale.ROOT));
    }

    public List<String> ids() {
        return new ArrayList<>(teams.keySet());
    }

    // --- admin mutation -----------------------------------------------------

    /** Add or update a team, persisting to config.yml. A blank prefix keeps any
     *  existing one; permissions are preserved across updates (edit in config). */
    public void addOrUpdate(String id, boolean priv, String rank, Material icon, String prefix) {
        String key = id.toLowerCase(Locale.ROOT);
        String path = "teams." + key;
        Team existing = teams.get(key);
        plugin.getConfig().set(path + ".display", existing != null ? existing.display() : "&f" + id);
        plugin.getConfig().set(path + ".type", priv ? "private" : "public");
        plugin.getConfig().set(path + ".rank", rank);
        plugin.getConfig().set(path + ".icon", icon.name());
        String newPrefix = (prefix != null && !prefix.isBlank()) ? prefix
            : (existing != null ? existing.prefix() : "");
        plugin.getConfig().set(path + ".prefix", newPrefix);
        plugin.getConfig().set(path + ".permissions",
            existing != null ? existing.permissions() : new ArrayList<String>());
        plugin.saveConfig();
        load();
    }

    /** Remove a team (and any grants to it), persisting. Returns false if unknown. */
    public boolean remove(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        if (!teams.containsKey(key)) return false;
        plugin.getConfig().set("teams." + key, null);
        plugin.getConfig().set("grants." + key, null);
        plugin.saveConfig();
        load();
        return true;
    }

    // --- team spawns --------------------------------------------------------

    /** A named spawn point for a team. */
    public record SpawnPoint(String name, Location loc) {}

    /** A team's spawn points, in order. Falls back to a legacy single spawn so
     *  teams set with the old /facility team setspawn still work. */
    public List<SpawnPoint> getSpawns(String teamId) {
        List<SpawnPoint> out = new ArrayList<>();
        if (teamId == null) return out;
        for (String raw : plugin.getConfig().getStringList("teams." + teamId.toLowerCase(Locale.ROOT) + ".spawns")) {
            SpawnPoint sp = decodeSpawn(raw);
            if (sp != null) out.add(sp);
        }
        if (out.isEmpty()) {
            Location legacy = legacySpawn(teamId);
            if (legacy != null) out.add(new SpawnPoint("Spawn", legacy));
        }
        return out;
    }

    /** "random" = pick one at random on deploy/respawn; "choose" = let the player
     *  pick from a menu. Default random. */
    public String getSpawnMode(String teamId) {
        return plugin.getConfig().getString("teams." + teamId.toLowerCase(Locale.ROOT) + ".spawn-mode", "random");
    }

    public void setSpawnMode(String teamId, String mode) {
        plugin.getConfig().set("teams." + teamId.toLowerCase(Locale.ROOT) + ".spawn-mode",
            mode.equalsIgnoreCase("choose") ? "choose" : "random");
        plugin.saveConfig();
    }

    /** Append a named spawn point. */
    public void addSpawn(String teamId, String name, Location loc) {
        String key = "teams." + teamId.toLowerCase(Locale.ROOT) + ".spawns";
        List<String> raw = plugin.getConfig().getStringList(key);
        raw.add(encodeSpawn(name, loc));
        plugin.getConfig().set(key, raw);
        plugin.saveConfig();
    }

    /** Replace all of a team's spawns with a single one (old setspawn behaviour). */
    public void setSpawn(String teamId, Location loc) {
        clearSpawns(teamId);
        addSpawn(teamId, "Spawn", loc);
    }

    /** Remove a spawn by 1-based index; false if out of range. */
    public boolean removeSpawn(String teamId, int oneBased) {
        String key = "teams." + teamId.toLowerCase(Locale.ROOT) + ".spawns";
        List<String> raw = plugin.getConfig().getStringList(key);
        int i = oneBased - 1;
        if (i < 0 || i >= raw.size()) return false;
        raw.remove(i);
        plugin.getConfig().set(key, raw);
        plugin.saveConfig();
        return true;
    }

    public void clearSpawns(String teamId) {
        String key = teamId.toLowerCase(Locale.ROOT);
        plugin.getConfig().set("teams." + key + ".spawns", null);
        plugin.getConfig().set("teams." + key + ".spawn", null);   // wipe legacy too
        plugin.saveConfig();
    }

    // encode/decode "name|world|x|y|z|yaw|pitch"
    private String encodeSpawn(String name, Location l) {
        return name.replace('|', ' ') + "|" + l.getWorld().getName() + "|" + l.getX() + "|"
            + l.getY() + "|" + l.getZ() + "|" + l.getYaw() + "|" + l.getPitch();
    }

    private SpawnPoint decodeSpawn(String s) {
        String[] p = s.split("\\|", 7);
        if (p.length < 7) return null;
        World world = Bukkit.getWorld(p[1]);
        if (world == null) return null;
        try {
            return new SpawnPoint(p[0], new Location(world, Double.parseDouble(p[2]),
                Double.parseDouble(p[3]), Double.parseDouble(p[4]),
                Float.parseFloat(p[5]), Float.parseFloat(p[6])));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The old single-spawn format, for backward compatibility. */
    private Location legacySpawn(String teamId) {
        String path = "teams." + teamId.toLowerCase(Locale.ROOT) + ".spawn";
        if (!plugin.getConfig().contains(path + ".world")) return null;
        World world = Bukkit.getWorld(plugin.getConfig().getString(path + ".world", ""));
        if (world == null) return null;
        return new Location(world,
            plugin.getConfig().getDouble(path + ".x"), plugin.getConfig().getDouble(path + ".y"),
            plugin.getConfig().getDouble(path + ".z"),
            (float) plugin.getConfig().getDouble(path + ".yaw"),
            (float) plugin.getConfig().getDouble(path + ".pitch"));
    }

    // --- private-team grants ------------------------------------------------

    /** Grant a player access to a private team. */
    public void grant(String teamId, UUID player) {
        String key = teamId.toLowerCase(Locale.ROOT);
        List<String> list = plugin.getConfig().getStringList("grants." + key);
        String id = player.toString();
        if (!list.contains(id)) list.add(id);
        plugin.getConfig().set("grants." + key, list);
        plugin.saveConfig();
    }

    /** Revoke a player's access to a private team. Returns true if they had it. */
    public boolean revoke(String teamId, UUID player) {
        String key = teamId.toLowerCase(Locale.ROOT);
        List<String> list = plugin.getConfig().getStringList("grants." + key);
        boolean had = list.remove(player.toString());
        plugin.getConfig().set("grants." + key, list);
        plugin.saveConfig();
        return had;
    }

    /** May this player join the team? Public: always. Private: only if granted. */
    public boolean mayJoin(Team team, Player player) {
        if (team == null) return false;
        if (!team.privateTeam()) return true;
        return plugin.getConfig().getStringList("grants." + team.id())
            .contains(player.getUniqueId().toString());
    }

    /**
     * Put the player into the team's LuckPerms group. The group already carries
     * the team's prefix + permissions (see {@link #syncGroups}), so `parent set`
     * hands the player the rank, the prefix and the perms in one move. The group
     * inherits `default`, so this doesn't strip their base permissions.
     *
     * Console-command based on purpose: no LuckPerms API dependency, robust
     * whether or not it's loaded. Absent LuckPerms, it no-ops and we log it.
     */
    public void applyRank(Player player, Team team) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            plugin.getLogger().warning("LuckPerms not present - '" + player.getName()
                + "' would have joined rank '" + team.rank() + "' (prefix + perms skipped).");
            return;
        }
        lp("user " + player.getName() + " parent set " + team.rank());
    }
}
