package fi.alavesa.facility;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Combat stats: counts deaths for the victim and a kill for the killer. Gun
 * deaths credit the shooter because Guns deals bullet damage through
 * target.damage(dmg, shooter), so getKiller() resolves to the gunman.
 */
public final class StatsListener implements Listener {

    private final PlayerStore store;

    public StatsListener(PlayerStore store) {
        this.store = store;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        store.addDeath(victim.getUniqueId());
        Player killer = victim.getKiller();
        if (killer != null && killer != victim) {
            store.addKill(killer.getUniqueId());
        }
    }
}
