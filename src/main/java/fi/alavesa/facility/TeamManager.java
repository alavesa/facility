package fi.alavesa.facility;

import org.bukkit.Bukkit;
import org.bukkit.Material;
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

    /** (Re)read every team from config.yml. */
    public void load() {
        teams.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("teams");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) continue;
            String key = id.toLowerCase(Locale.ROOT);
            String display = sec.getString("display", "&f" + id);
            boolean priv = "private".equalsIgnoreCase(sec.getString("type", "public"));
            String rank = sec.getString("rank", key);
            Material icon = Material.matchMaterial(sec.getString("icon", "PAPER"));
            if (icon == null) icon = Material.PAPER;
            teams.put(key, new Team(key, display, priv, rank, icon));
        }
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

    /** Add or update a team, persisting to config.yml. */
    public void addOrUpdate(String id, boolean priv, String rank, Material icon) {
        String key = id.toLowerCase(Locale.ROOT);
        String path = "teams." + key;
        Team existing = teams.get(key);
        plugin.getConfig().set(path + ".display", existing != null ? existing.display() : "&f" + id);
        plugin.getConfig().set(path + ".type", priv ? "private" : "public");
        plugin.getConfig().set(path + ".rank", rank);
        plugin.getConfig().set(path + ".icon", icon.name());
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
     * Put the player into the team's LuckPerms rank via the console
     * `lp user <name> parent set <rank>` fallback. Console-command based on
     * purpose: no LuckPerms API dependency, robust whether or not it's loaded.
     */
    public void applyRank(Player player, Team team) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "lp user " + player.getName() + " parent set " + team.rank());
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            plugin.getLogger().warning("LuckPerms not present - '" + player.getName()
                + "' would have been set to rank '" + team.rank() + "'.");
        }
    }
}
