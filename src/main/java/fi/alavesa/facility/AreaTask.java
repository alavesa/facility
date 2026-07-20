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

    @Override
    public void run() {
        Objective z008 = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            AreaManager.Area area = areas.areaAt(player.getLocation());
            String name = area == null ? null : area.name();

            // last area (only write on change - it's persisted)
            String prev = store.lastArea(player.getUniqueId());
            if (name != null && !name.equals(prev)) {
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
            if (area.scp008()) {
                if (z008 == null) z008 = infectionObjective();
                if (z008 != null) {
                    var score = z008.getScore(player.getName());
                    score.setScore(Math.min(200, (score.isScoreSet() ? score.getScore() : 0) + 3));
                }
            }
        }
    }

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
