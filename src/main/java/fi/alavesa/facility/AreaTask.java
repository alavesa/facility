package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;

import java.util.Locale;

/**
 * Ticks every second: works out which named area each player stands in, records
 * it as their "last area" (for stats + the tab-list readout), applies the area's
 * potion effects, and slowly feeds Labra's SCP-008 infection meter (lab.z008) for
 * rooms flagged scp008. Also a moderation aid - the tab footer shows where
 * everyone is at a glance.
 */
public final class AreaTask implements Runnable {

    private final FacilityPlugin plugin;
    private final AreaManager areas;
    private final PlayerStore store;

    public AreaTask(FacilityPlugin plugin, AreaManager areas, PlayerStore store) {
        this.plugin = plugin;
        this.areas = areas;
        this.store = store;
    }

    /** Seconds since enable - drives the "every N seconds" ambient sound cadence. */
    private int seconds;

    @Override
    public void run() {
        seconds++;
        Objective z008 = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            AreaManager.Area area = areas.areaAt(player.getLocation());
            String name = area == null ? null : area.name();

            // last area (only write on change - it's persisted). Capture whether this is the
            // tick they ENTERED the area (for one-shot "on enter" sound cues).
            String prev = store.lastArea(player.getUniqueId());
            boolean entered = name != null && !name.equals(prev);
            if (entered) {
                store.setLastArea(player.getUniqueId(), name);
            }

            // tab-list footer readout
            player.sendPlayerListFooter(name == null
                ? Component.text("Area: —", NamedTextColor.DARK_GRAY)
                : Component.text("Area: ", NamedTextColor.GRAY).append(Component.text(name, NamedTextColor.AQUA)));

            if (area == null) continue;
            if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) continue;

            for (String spec : area.effects()) {
                PotionEffect eff = parse(spec);
                if (eff != null) player.addPotionEffect(eff);
            }
            for (String spec : area.sounds()) {
                playAreaSound(player, spec, entered);
            }
            if (area.scp008()) {
                if (z008 == null) z008 = infectionObjective();
                if (z008 != null) {
                    var score = z008.getScore(player.getName());
                    score.setScore(Math.min(200, (score.isScoreSet() ? score.getScore() : 0) + 3));
                }
            }
        }
    }

    /** Play an area sound cue "key|volume|pitch|everySeconds" to the player. Any resource-pack
     *  sound event works (the key is passed through as a string). everySeconds 0 = only on the
     *  tick they entered the area; >0 = re-play every N seconds while they stand inside. */
    private void playAreaSound(Player player, String spec, boolean entered) {
        String[] f = spec.split("\\|");
        if (f.length == 0 || f[0].isBlank()) return;
        String key = f[0].trim();
        float vol = f.length > 1 ? parseF(f[1], 1f) : 1f;
        float pitch = f.length > 2 ? parseF(f[2], 1f) : 1f;
        int every = f.length > 3 ? parseInt(f[3], 0) : 0;
        boolean play = every <= 0 ? entered : (seconds % every == 0);
        if (play) player.playSound(player.getLocation(), key, org.bukkit.SoundCategory.AMBIENT, vol, pitch);
    }

    private float parseF(String s, float def) { try { return Float.parseFloat(s.trim()); } catch (Exception e) { return def; } }
    private int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }

    /** "TYPE:amplifier" -> a 1.5s effect (re-applied each second so it never lapses). */
    private PotionEffect parse(String spec) {
        String[] p = spec.split(":");
        PotionEffectType type = PotionEffectType.getByName(p[0].trim().toUpperCase(Locale.ROOT));
        if (type == null) return null;
        int amp = 0;
        if (p.length > 1) { try { amp = Integer.parseInt(p[1].trim()); } catch (NumberFormatException ignored) { } }
        return new PotionEffect(type, 30, Math.max(0, amp), true, false, false);
    }

    private Objective infectionObjective() {
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective obj = board.getObjective("lab.z008");
        if (obj == null) {
            try { obj = board.registerNewObjective("lab.z008", Criteria.DUMMY, Component.text("z008")); }
            catch (IllegalArgumentException e) { obj = board.getObjective("lab.z008"); }
        }
        return obj;
    }
}
