package fi.alavesa.facility;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat tagging + combat logging.
 *
 * PvP (direct or by projectile) tags BOTH players for COMBAT_LOG_SECONDS.
 * Re-taking damage refreshes the timer. While tagged, a boss bar counts down:
 *
 *     &7Combat Log &8| &4<seconds>
 *
 * parsed with LegacyComponentSerializer.legacyAmpersand() so the colours are
 * EXACTLY gray "Combat Log ", dark_gray "| ", dark_red "<n>". The bar's
 * progress drains with the timer and it's removed at zero.
 *
 * Cross-plugin bossbar ordering is best-effort: Labra's HudTask shows a vitals
 * bar at the top; we add ours here, so on most clients it stacks BELOW the
 * vitals bar. The client sorts by add order and we can't force a slot, so this
 * is the best we can do.
 *
 * Logging out while tagged is treated as a death: we kill the quitter (so they
 * drop / take the death) and store a pending combat-log death message that the
 * lobby shows as a hologram the next time they Continue.
 */
public final class CombatLogListener implements Listener, Runnable {

    /** Seconds a hit keeps you in combat. */
    public static final int COMBAT_LOG_SECONDS = 15;

    private final FacilityPlugin plugin;
    private final PlayerStore store;

    private final Map<UUID, Long> taggedUntil = new ConcurrentHashMap<>();   // ms epoch
    private final Map<UUID, UUID> lastAttacker = new ConcurrentHashMap<>();  // victim -> attacker
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public CombatLogListener(FacilityPlugin plugin, PlayerStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    // --- tagging ------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) return;
        tag(victim, attacker);
        tag(attacker, victim);
    }

    /** Melee attacker, or the shooter behind a projectile. */
    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }

    /** Tag (or refresh) the victim, remembering who last hit them. */
    private void tag(Player victim, Player attacker) {
        taggedUntil.put(victim.getUniqueId(), System.currentTimeMillis() + COMBAT_LOG_SECONDS * 1000L);
        lastAttacker.put(victim.getUniqueId(), attacker.getUniqueId());
    }

    public boolean isTagged(UUID id) {
        Long until = taggedUntil.get(id);
        return until != null && until > System.currentTimeMillis();
    }

    // --- combat logging -----------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!isTagged(id)) return;

        // Build a vanilla-flavoured death message from the last damage cause.
        String message = deathMessage(player);
        store.setPendingCombatDeath(id, message);

        // Treat it as a death so logging out can't dodge the fight: kill them,
        // which drops their inventory / applies the death, right now.
        try {
            player.setHealth(0.0);
        } catch (IllegalArgumentException ignored) {
            // already dead / invalid - the pending record is enough
        }
        clearBar(player);
        taggedUntil.remove(id);
        lastAttacker.remove(id);
        plugin.getLogger().info("Combat log: " + player.getName() + " quit while tagged - "
            + "treated as a death (\"" + message + "\").");
    }

    /** A vanilla-like death message, preferring the last PvP attacker. */
    private String deathMessage(Player player) {
        UUID attackerId = lastAttacker.get(player.getUniqueId());
        if (attackerId != null) {
            Player attacker = Bukkit.getPlayer(attackerId);
            String name = attacker != null ? attacker.getName() : "an attacker";
            return player.getName() + " was slain by " + name;
        }
        EntityDamageEvent last = player.getLastDamageCause();
        if (last != null) {
            return player.getName() + " died (" + last.getCause().name().toLowerCase() + ")";
        }
        return player.getName() + " was slain";
    }

    // --- the countdown bossbar ---------------------------------------------

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Long until = taggedUntil.get(id);
            if (until == null) continue;
            long remainingMs = until - now;
            if (remainingMs <= 0) {
                taggedUntil.remove(id);
                clearBar(player);
                continue;
            }
            int seconds = (int) Math.ceil(remainingMs / 1000.0);
            // EXACT legacy string: gray "Combat Log ", dark_gray "| ", dark_red "<n>"
            Component title = LegacyComponentSerializer.legacyAmpersand()
                .deserialize("&7Combat Log &8| &4" + seconds);
            float progress = Math.max(0f, Math.min(1f, remainingMs / (COMBAT_LOG_SECONDS * 1000f)));

            BossBar bar = bars.get(id);
            if (bar == null) {
                // Added AFTER Labra's vitals bar, so it stacks below it (best-effort).
                bar = BossBar.bossBar(title, progress, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
                bars.put(id, bar);
                player.showBossBar(bar);
            } else {
                bar.name(title);
                bar.progress(progress);
            }
        }
        // sweep bars for players who logged off
        bars.keySet().removeIf(id -> {
            Player online = plugin.getServer().getPlayer(id);
            if (online == null) return true;
            if (!isTagged(id)) {
                online.hideBossBar(bars.get(id));
                return true;
            }
            return false;
        });
    }

    private void clearBar(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    public void shutdown() {
        for (UUID id : bars.keySet()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) player.hideBossBar(bars.get(id));
        }
        bars.clear();
    }
}
