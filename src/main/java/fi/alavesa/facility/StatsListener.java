package fi.alavesa.facility;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Combat stats: counts deaths for the victim and a kill for the killer.
 *
 * Gun deaths normally credit the shooter because Guns deals bullet damage through
 * {@code target.damage(dmg, shooter)}, so {@code getKiller()} resolves to the
 * gunman. But when PvP is off, that credited hit is cancelled and Guns re-applies
 * the damage SOURCE-LESS - which kills the player with no vanilla killer. For that
 * case Guns stamps the shooter (+ time) on the victim's PDC in its own "guns"
 * namespace, and we fall back to reading it here so gun kills still count.
 */
public final class StatsListener implements Listener {

    private final PlayerStore store;

    /** Written by the Guns plugin on a victim when a bullet lands (namespace "guns"). */
    private static final NamespacedKey GUN_ATTACKER = NamespacedKey.fromString("guns:gun_attacker");
    private static final NamespacedKey GUN_ATTACKER_AT = NamespacedKey.fromString("guns:gun_attacker_at");
    /** Only credit a gun kill if the last hit was recent, so an old tag can't. */
    private static final long GUN_CREDIT_WINDOW_MS = 10_000;

    public StatsListener(PlayerStore store) {
        this.store = store;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        store.addDeath(victim.getUniqueId());

        UUID killerId = null;
        Player killer = victim.getKiller();
        if (killer != null) {
            killerId = killer.getUniqueId();
        } else if (GUN_ATTACKER != null && GUN_ATTACKER_AT != null) {
            // no vanilla killer (a gun hit with PvP off is source-less) - use the
            // last gun attacker Guns tagged on the victim, if it's recent enough.
            var pdc = victim.getPersistentDataContainer();
            String id = pdc.get(GUN_ATTACKER, PersistentDataType.STRING);
            Long at = pdc.get(GUN_ATTACKER_AT, PersistentDataType.LONG);
            if (id != null && at != null && System.currentTimeMillis() - at <= GUN_CREDIT_WINDOW_MS) {
                try { killerId = UUID.fromString(id); } catch (IllegalArgumentException ignored) { }
            }
        }
        if (killerId != null && !killerId.equals(victim.getUniqueId())) {
            store.addKill(killerId);
        }
    }
}
