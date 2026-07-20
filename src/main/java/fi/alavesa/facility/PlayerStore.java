package fi.alavesa.facility;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Per-player persistence: last logout location, the gamemode we should restore
 * them to on Continue, and any pending combat-log "death" that must be shown
 * as a hologram the next time they press Continue. Everything survives a
 * restart because it's flat YAML under data/players/<uuid>.yml.
 *
 * PDC would work for the location, but a pending combat-log death has to
 * survive even the death of the player object at logout, so a data file keeps
 * the whole record in one honest place.
 */
public final class PlayerStore {

    private final FacilityPlugin plugin;
    private final File dir;

    public PlayerStore(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "players");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create player data dir: " + dir);
        }
    }

    private File file(UUID id) {
        return new File(dir, id + ".yml");
    }

    private YamlConfiguration read(UUID id) {
        return YamlConfiguration.loadConfiguration(file(id));
    }

    private void write(UUID id, YamlConfiguration cfg) {
        try {
            cfg.save(file(id));
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save player data for " + id + ": " + e.getMessage());
        }
    }

    // --- logout snapshot ----------------------------------------------------

    /** Snapshot where they logged out and the gamemode to restore. Skips
     *  spectator (the menu lock) so we never restore them into a frozen state. */
    public void saveLogout(Player player) {
        YamlConfiguration cfg = read(player.getUniqueId());
        Location loc = player.getLocation();
        cfg.set("last.world", loc.getWorld().getName());
        cfg.set("last.x", loc.getX());
        cfg.set("last.y", loc.getY());
        cfg.set("last.z", loc.getZ());
        cfg.set("last.yaw", loc.getYaw());
        cfg.set("last.pitch", loc.getPitch());
        GameMode gm = player.getGameMode();
        if (gm == GameMode.SPECTATOR) gm = GameMode.SURVIVAL;   // never persist the lock
        cfg.set("last.gamemode", gm.name());
        write(player.getUniqueId(), cfg);
    }

    /** The saved logout location, or null if we've never seen them. */
    public Location lastLocation(UUID id) {
        YamlConfiguration cfg = read(id);
        if (!cfg.contains("last.world")) return null;
        World world = Bukkit.getWorld(cfg.getString("last.world", ""));
        if (world == null) return null;
        return new Location(world,
            cfg.getDouble("last.x"), cfg.getDouble("last.y"), cfg.getDouble("last.z"),
            (float) cfg.getDouble("last.yaw"), (float) cfg.getDouble("last.pitch"));
    }

    /** The gamemode to restore on Continue - SURVIVAL if we have nothing. */
    public GameMode lastGameMode(UUID id) {
        String name = read(id).getString("last.gamemode", "SURVIVAL");
        try {
            GameMode gm = GameMode.valueOf(name);
            return gm == GameMode.SPECTATOR ? GameMode.SURVIVAL : gm;
        } catch (IllegalArgumentException e) {
            return GameMode.SURVIVAL;
        }
    }

    /** Have they ever logged in before (i.e. do we have a record)? */
    public boolean isKnown(UUID id) {
        return file(id).exists();
    }

    // --- selected team ------------------------------------------------------

    /** Remember which team the player last joined (so Continue + respawn can use
     *  the team's spawn). Survives restart. */
    public void setTeam(UUID id, String teamId) {
        YamlConfiguration cfg = read(id);
        cfg.set("team", teamId);
        write(id, cfg);
    }

    /** The player's current team id, or null. */
    public String getTeam(UUID id) {
        return read(id).getString("team", null);
    }

    // --- pending combat-log death ------------------------------------------

    /** Record a combat-log death message to be shown as a hologram next Continue. */
    public void setPendingCombatDeath(UUID id, String message) {
        YamlConfiguration cfg = read(id);
        cfg.set("combatlog.pending", message);
        write(id, cfg);
    }

    /** The pending combat-log death message, or null. */
    public String pendingCombatDeath(UUID id) {
        return read(id).getString("combatlog.pending", null);
    }

    /** Clear the pending death once its hologram has been shown. */
    public void clearPendingCombatDeath(UUID id) {
        YamlConfiguration cfg = read(id);
        cfg.set("combatlog.pending", null);
        write(id, cfg);
    }

    // --- combat stats -------------------------------------------------------

    public int kills(UUID id)  { return read(id).getInt("stats.kills", 0); }
    public int deaths(UUID id) { return read(id).getInt("stats.deaths", 0); }

    public void addKill(UUID id) {
        YamlConfiguration cfg = read(id);
        cfg.set("stats.kills", cfg.getInt("stats.kills", 0) + 1);
        write(id, cfg);
    }

    public void addDeath(UUID id) {
        YamlConfiguration cfg = read(id);
        cfg.set("stats.deaths", cfg.getInt("stats.deaths", 0) + 1);
        write(id, cfg);
    }

    /** Kill/death ratio (deaths treated as at least 1 so a clean sheet still reads sensibly). */
    public double kd(UUID id) {
        int k = kills(id), d = deaths(id);
        return d == 0 ? k : Math.round(k / (double) d * 100.0) / 100.0;
    }

    // --- last area (set by AreaManager, shown in stats + tab) ---------------

    public void setLastArea(UUID id, String area) {
        YamlConfiguration cfg = read(id);
        cfg.set("last-area", area);
        write(id, cfg);
    }

    public String lastArea(UUID id) {
        return read(id).getString("last-area", null);
    }
}
