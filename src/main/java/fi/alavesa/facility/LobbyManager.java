package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashSet;
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
 * DeluxeMenus draws the menu when present ({@code deluxemenus open mainmenu});
 * absent, a built-in fallback keeps the plugin self-sufficient.
 */
public final class LobbyManager implements Listener {

    private final FacilityPlugin plugin;
    private final PlayerStore store;
    private final FallbackMenu fallback;

    /** Players who have Continued this session and shouldn't be re-locked. */
    private final Set<UUID> continued = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public LobbyManager(FacilityPlugin plugin, PlayerStore store, FallbackMenu fallback) {
        this.plugin = plugin;
        this.store = store;
        this.fallback = fallback;
    }

    // --- join / quit --------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        continued.remove(player.getUniqueId());   // every session starts locked
        // Lock a tick later so other join handlers (and the client) settle first.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> lock(player), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Only snapshot a real, in-world position - never the menu lock itself.
        if (continued.contains(player.getUniqueId()) && player.getGameMode() != GameMode.SPECTATOR) {
            store.saveLogout(player);
        }
        continued.remove(player.getUniqueId());
    }

    /** Freeze the player at the lobby vantage in spectator and open the menu. */
    private void lock(Player player) {
        if (!player.isOnline() || continued.contains(player.getUniqueId())) return;
        player.setGameMode(GameMode.SPECTATOR);
        Location vantage = lobbyVantage(player);
        if (vantage != null) player.teleport(vantage);
        openMainMenu(player);
    }

    /** Open the DeluxeMenus main menu if present, else the built-in fallback. */
    public void openMainMenu(Player player) {
        if (Bukkit.getPluginManager().getPlugin("DeluxeMenus") != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "deluxemenus open mainmenu " + player.getName());
        } else {
            fallback.open(player);
        }
    }

    // --- continue -----------------------------------------------------------

    /** The Continue button lands here. Return them to the world and unlock. */
    public void continueInto(Player player) {
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
