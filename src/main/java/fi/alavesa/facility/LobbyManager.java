package fi.alavesa.facility;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The lobby lock. A new arrival (first join, or anyone who hasn't Continued
 * this session) is dropped into SPECTATOR at the lobby vantage and shown the
 * main menu. Pressing Continue ({@code /facility continue}) returns them to
 * their last logout location, restores their gamemode, and drops them into the
 * world proper. If they had a pending combat-log death we float a hologram of
 * it in front of them once they land.
 *
 * The menu is drawn by {@link DialogMenu} (native /dialog). The ONLY time a
 * player is thrown to the menu automatically is on rejoin. Death/respawn never
 * locks them (see {@link #onRespawn}); {@code /menu} lets them return by hand -
 * but re-entry by hand requires a 10-second STAND-STILL hold first (config
 * {@code menu.reentry-seconds}), while the initial join-lock opens immediately.
 *
 * Compatibility: a player who is inside a Terminal CCTV session is in spectator
 * mode for another plugin's reasons. We detect that by Terminal's PDC key
 * ({@code terminal:cctv_back}) and NEVER lock, teleport, restore gamemode over,
 * or reopen the dialog on such a player.
 */
public final class LobbyManager implements Listener {

    private final FacilityPlugin plugin;
    private final PlayerStore store;
    private final DialogMenu dialogMenu;

    /** Players who have Continued this session and shouldn't be re-locked. */
    private final Set<UUID> continued = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** In-flight re-entry stand-still holds (from /menu), keyed by player. */
    private final Map<UUID, BukkitTask> reentryHolds = new java.util.concurrent.ConcurrentHashMap<>();

    /** Looping main-menu music tasks, keyed by player (one per player in menu). */
    private final Map<UUID, BukkitTask> musicTasks = new java.util.concurrent.ConcurrentHashMap<>();

    /** Players who joined and still need the menu opened once their client is
     *  ready (opened on the resource-pack status, or a timed fallback). */
    private final Set<UUID> pendingMenu = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Terminal's CCTV crash-backup PDC key; its presence means an active (or
     *  crashed) CCTV session. namespace = plugin name lowercased = "terminal". */
    private static final NamespacedKey CCTV_BACK = new NamespacedKey("terminal", "cctv_back");

    public LobbyManager(FacilityPlugin plugin, PlayerStore store, DialogMenu dialogMenu) {
        this.plugin = plugin;
        this.store = store;
        this.dialogMenu = dialogMenu;
    }

    // --- join / quit --------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        continued.remove(player.getUniqueId());   // every session starts locked
        // Swiftly freeze the arrival: spectator IMMEDIATELY (unless exempt) so
        // they can't move/act while the client settles...
        if (!inCctv(player) && player.getGameMode() != GameMode.CREATIVE) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        // ...but open the dialog only once the client is READY. A dialog shown
        // mid-load (resource-pack download, ViaVersion, LuckPerms login) is
        // silently dropped - that's why it "didn't open on join" while /menu did.
        // We open it on the resource-pack status (client past loading), with a
        // ~5s timed fallback for clients that never send an RP status.
        pendingMenu.add(player.getUniqueId());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> tryOpenJoinMenu(player), 100L);
    }

    /** The client finished (or refused) the resource pack - it's now in the world
     *  and ready for a dialog. Open the pending join menu, once. */
    @EventHandler
    public void onResourcePack(PlayerResourcePackStatusEvent event) {
        PlayerResourcePackStatusEvent.Status st = event.getStatus();
        if (st == PlayerResourcePackStatusEvent.Status.ACCEPTED
            || st == PlayerResourcePackStatusEvent.Status.DOWNLOADED) return;   // not terminal yet
        Player player = event.getPlayer();
        if (!pendingMenu.contains(player.getUniqueId())) return;
        // a short settle delay after the RP phase, then open
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> tryOpenJoinMenu(player), 10L);
    }

    /** Open the join menu exactly once, whichever trigger (RP status or the timed
     *  fallback) gets here first. */
    private void tryOpenJoinMenu(Player player) {
        if (!pendingMenu.remove(player.getUniqueId())) return;   // already opened, or gone
        if (!player.isOnline()) return;
        lock(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cancelReentry(player, null);
        stopMusic(player);
        pendingMenu.remove(player.getUniqueId());
        // Only snapshot a real, in-world position - never the menu lock itself,
        // and never a CCTV spectator session (Terminal owns that restore).
        if (continued.contains(player.getUniqueId())
            && player.getGameMode() != GameMode.SPECTATOR
            && !inCctv(player)) {
            store.saveLogout(player);
        }
        continued.remove(player.getUniqueId());
    }

    /**
     * Dying must NEVER throw a player back to the menu - the menu is a rejoin-
     * only thing. A player who has already Continued this session stays
     * Continued through death, so no join-style lock can fire on the respawn.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (continued.contains(player.getUniqueId())) return;   // already in-world: leave them be
        continued.add(player.getUniqueId());
    }

    /**
     * The AUTOMATIC join-lock. Applies the exemptions: never hijack a Terminal
     * CCTV session, a creative session, or (unless configured) an op - those
     * players skip the lobby entirely. Everyone else is force-locked.
     */
    private void lock(Player player) {
        if (!player.isOnline() || continued.contains(player.getUniqueId())) return;
        if (inCctv(player)) return;
        // Creative sessions (builders/admins mid-work) are never locked; everyone
        // else - ops included - goes through the lobby and just presses PLAY.
        if (player.getGameMode() == GameMode.CREATIVE) {
            continued.add(player.getUniqueId());   // treat as already in-world
            return;
        }
        forceLock(player);
    }

    /**
     * Actually put the player in the menu: spectator, at the vantage, dialog
     * open. No exemptions - this is what an EXPLICIT /menu runs, so it works for
     * ops and everyone else. (CCTV is still refused upstream in returnToMenu.)
     */
    private void forceLock(Player player) {
        if (!player.isOnline()) return;
        player.setGameMode(GameMode.SPECTATOR);
        Location vantage = lobbyVantage(player);
        if (vantage != null) player.teleport(vantage);
        openMainMenu(player);
        startMusic(player);
    }

    // --- main-menu music ----------------------------------------------------

    /**
     * Play the configured menu track now and loop it every
     * {@code menu.music.interval-minutes} while the player is still in the menu.
     * {@code menu.music.sound} is any sound key - a vanilla one or a custom one
     * added to the resource pack. No-op / cheap if disabled.
     */
    private void startMusic(Player player) {
        stopMusic(player);   // never stack two loops
        if (!plugin.getConfig().getBoolean("menu.music.enabled", true)) return;
        String soundName = plugin.getConfig().getString("menu.music.sound", "").trim();
        if (soundName.isEmpty()) return;
        final Key key;
        try { key = Key.key(soundName); } catch (Exception invalid) {
            plugin.getLogger().warning("menu.music.sound '" + soundName + "' is not a valid sound key.");
            return;
        }
        float volume = (float) plugin.getConfig().getDouble("menu.music.volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("menu.music.pitch", 1.0);
        double minutes = Math.max(0.05, plugin.getConfig().getDouble("menu.music.interval-minutes", 3.0));
        long periodTicks = Math.max(20L, (long) (minutes * 60.0 * 20.0));
        Sound sound = Sound.sound(key, Sound.Source.MUSIC, volume, pitch);

        player.playSound(sound);   // immediately
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            // Still online AND still in the menu? Otherwise stop the loop.
            if (!player.isOnline() || continued.contains(player.getUniqueId())
                || player.getGameMode() != GameMode.SPECTATOR) {
                stopMusic(player);
                return;
            }
            player.playSound(sound);
        }, periodTicks, periodTicks);
        musicTasks.put(player.getUniqueId(), task);
    }

    /** Stop the menu music loop and silence the current track. */
    private void stopMusic(Player player) {
        BukkitTask task = musicTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
        String soundName = plugin.getConfig().getString("menu.music.sound", "").trim();
        if (!soundName.isEmpty() && player.isOnline()) {
            try { player.stopSound(SoundStop.named(Key.key(soundName))); } catch (Exception ignored) { }
        }
    }

    /** Open the custom main menu (a native /dialog GUI). The dialog itself is
     *  un-escapable ({@code can_close_with_escape:false}), so it is the lobby
     *  lock - no movement guard needed. */
    public void openMainMenu(Player player) {
        dialogMenu.openMain(player);
    }

    // --- /menu re-entry: 10-second stand-still hold --------------------------

    /**
     * {@code /menu}: send the player back to the menu by hand - but not
     * instantly. Start a stand-still hold: they must not move a block or take
     * damage for {@code menu.reentry-seconds} seconds. Moving/being hit cancels
     * it. On completion we lock them exactly like a fresh rejoin.
     *
     * The initial join-lock does NOT go through here (it calls {@link #lock}
     * directly), so new arrivals still open the menu immediately.
     */
    public void returnToMenu(Player player) {
        if (inCctv(player)) {
            player.sendActionBar(Component.text("Not while jacked into CCTV.", NamedTextColor.RED));
            return;
        }
        if (continued.contains(player.getUniqueId()) && player.getGameMode() == GameMode.SPECTATOR) {
            // Already at the menu (or free-spectating); just reopen it.
            openMainMenu(player);
            return;
        }
        if (reentryHolds.containsKey(player.getUniqueId())) return;   // already counting

        int seconds = Math.max(0, plugin.getConfig().getInt("menu.reentry-seconds", 10));
        if (seconds == 0) {   // hold disabled: behave like the old instant /menu
            doReturnToMenu(player);
            return;
        }

        final Location anchor = player.getLocation().clone();
        final long endAt = System.currentTimeMillis() + seconds * 1000L;
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) { cancelReentry(player, null); return; }
            // Moved a full block on any axis? cancel.
            Location now = player.getLocation();
            if (now.getBlockX() != anchor.getBlockX()
                || now.getBlockY() != anchor.getBlockY()
                || now.getBlockZ() != anchor.getBlockZ()) {
                cancelReentry(player, "Cancelled - you moved.");
                return;
            }
            long remainMs = endAt - System.currentTimeMillis();
            if (remainMs <= 0) {
                cancelReentry(player, null);
                doReturnToMenu(player);
                return;
            }
            int remain = (int) Math.ceil(remainMs / 1000.0);
            player.sendActionBar(Component.text("Returning to menu in " + remain
                + "s... hold still", NamedTextColor.AQUA));
        }, 0L, 5L);   // 4 Hz, smooth countdown
        reentryHolds.put(player.getUniqueId(), task);
    }

    /** Cancel a running re-entry hold, optionally telling the player why. */
    private void cancelReentry(Player player, String reason) {
        BukkitTask task = reentryHolds.remove(player.getUniqueId());
        if (task != null) task.cancel();
        if (reason != null && player.isOnline()) {
            player.sendActionBar(Component.text(reason, NamedTextColor.RED));
        }
    }

    /** Taking damage cancels an in-flight stand-still hold. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (reentryHolds.containsKey(player.getUniqueId())) {
            cancelReentry(player, "Cancelled - you took damage.");
        }
    }

    /** The actual re-lock, once the stand-still hold completes. Re-locks them
     *  into spectator at the vantage and reopens the menu, exactly like a fresh
     *  rejoin - their return point is snapshotted first so Continue still works. */
    private void doReturnToMenu(Player player) {
        if (inCctv(player)) return;   // defensive: never re-lock over CCTV
        if (player.getGameMode() != GameMode.SPECTATOR) store.saveLogout(player);
        continued.remove(player.getUniqueId());
        // Explicit /menu bypasses the op/creative auto-lock exemptions: if you
        // asked for the menu, you get it, whoever you are.
        forceLock(player);
    }

    // --- continue -----------------------------------------------------------

    /** The Continue button lands here. Return them to the world and unlock. */
    public void continueInto(Player player) {
        cancelReentry(player, null);
        stopMusic(player);
        continued.add(player.getUniqueId());
        player.closeInventory();

        Location dest = store.lastLocation(player.getUniqueId());
        if (dest == null) {
            World world = player.getWorld();
            dest = world.getSpawnLocation();
        }
        GameMode gm = store.lastGameMode(player.getUniqueId());
        player.setGameMode(gm);
        final Location target = dest;
        player.teleport(target);

        player.sendMessage(Component.text("Welcome to Site-19. Stay sharp.", NamedTextColor.AQUA));

        // Show any pending combat-log death as a hologram once they land.
        String pending = store.pendingCombatDeath(player.getUniqueId());
        if (pending != null) {
            store.clearPendingCombatDeath(player.getUniqueId());
            plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> spawnDeathHologram(player, pending), 10L);
        }
    }

    public boolean hasContinued(UUID id) {
        return continued.contains(id);
    }

    // --- compatibility helpers ----------------------------------------------

    /** Is this player inside a Terminal CCTV session? Detected softly by the
     *  crash-backup PDC key Terminal sets before it flips them to spectator, so
     *  no hard dependency on Terminal is needed. */
    private boolean inCctv(Player player) {
        try {
            return player.getPersistentDataContainer().has(CCTV_BACK, PersistentDataType.STRING);
        } catch (Exception ignored) {
            return false;
        }
    }

    // --- lobby vantage ------------------------------------------------------

    private Location lobbyVantage(Player player) {
        String worldName = plugin.getConfig().getString("lobby.world", "");
        World world = worldName == null || worldName.isBlank()
            ? player.getWorld() : Bukkit.getWorld(worldName);
        if (world == null) world = player.getWorld();
        if (worldName == null || worldName.isBlank()) {
            // No configured vantage: freeze them right where they are.
            return null;
        }
        return new Location(world,
            plugin.getConfig().getDouble("lobby.x", 0.5),
            plugin.getConfig().getDouble("lobby.y", 100.0),
            plugin.getConfig().getDouble("lobby.z", 0.5),
            (float) plugin.getConfig().getDouble("lobby.yaw", 0.0),
            (float) plugin.getConfig().getDouble("lobby.pitch", 0.0));
    }

    // --- combat-log rejoin hologram ----------------------------------------

    /**
     * Float a TextDisplay a couple of blocks ahead of the player at eye level,
     * facing them, showing their combat-log death message. It self-destructs
     * after ~10s or when they walk away.
     */
    private void spawnDeathHologram(Player player, String deathMessage) {
        if (!player.isOnline()) return;
        Location eye = player.getEyeLocation();
        Location spot = eye.clone().add(eye.getDirection().normalize().multiply(2.0));

        Component text = Component.text()
            .append(LegacyComponentSerializer.legacyAmpersand().deserialize("&c" + deathMessage))
            .append(Component.newline())
            .append(Component.text("Combat logged", NamedTextColor.GRAY))
            .build();

        World world = spot.getWorld();
        TextDisplay display = world.spawn(spot, TextDisplay.class, td -> {
            td.text(text);
            td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);   // always faces the viewer
            td.setSeeThrough(true);
            td.setShadowed(true);
            td.setDefaultBackground(false);
            td.setBackgroundColor(org.bukkit.Color.fromARGB(160, 0, 0, 0));
            td.setTransformation(new Transformation(
                new Vector3f(), new AxisAngle4f(), new Vector3f(1.2f, 1.2f, 1.2f), new AxisAngle4f()));
        });

        Location anchor = player.getLocation().clone();
        final int[] ticks = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            ticks[0] += 5;
            boolean gone = !display.isValid();
            boolean movedAway = !player.isOnline()
                || player.getLocation().distanceSquared(anchor) > 16.0;   // ~4 blocks
            if (gone || movedAway || ticks[0] >= 200) {   // 200 ticks = 10s
                if (display.isValid()) display.remove();
                task.cancel();
            }
        }, 5L, 5L);
    }
}
