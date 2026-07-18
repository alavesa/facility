package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A facility-wide blackout. When it starts, every WAXED COPPER TRAPDOOR that is
 * a light fixture - TOP half, facing WEST or EAST - is switched to its unpowered
 * state ({@code powered=false}) so the pack model reads as "off", and every
 * {@code minecraft:light} block within {@code blackout.light-radius} of such a
 * fixture is removed, plunging the site into darkness. When the blackout ends
 * (manually, after a timer, or on plugin shutdown) every touched trapdoor and
 * light block is restored to exactly what it was.
 *
 * The scan is spread across ticks ({@code blackout.chunks-per-tick}) so toggling
 * it never freezes the server - the lights cut out in a quick cascade instead.
 */
public final class BlackoutManager {

    private final FacilityPlugin plugin;

    /** Everything we changed -> its original BlockData, so end() restores exactly. */
    private final Map<Location, BlockData> undo = new ConcurrentHashMap<>();

    private volatile boolean active;
    private BukkitTask scanTask;
    private BukkitTask endTask;

    public BlackoutManager(FacilityPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return active;
    }

    /** Begin a blackout. {@code seconds <= 0} lasts until {@link #end()} is called. */
    public void start(int seconds) {
        if (active) return;
        active = true;
        undo.clear();

        int minY = plugin.getConfig().getInt("blackout.min-y", 0);
        int maxY = plugin.getConfig().getInt("blackout.max-y", 128);
        int radius = Math.max(0, plugin.getConfig().getInt("blackout.light-radius", 2));
        int perTick = Math.max(1, plugin.getConfig().getInt("blackout.chunks-per-tick", 4));

        Deque<Chunk> queue = new ArrayDeque<>(scanScope());
        broadcast("&8&lThe facility plunges into darkness.", Sound.BLOCK_BEACON_DEACTIVATE);

        scanTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (int n = 0; n < perTick && !queue.isEmpty(); n++) {
                Chunk chunk = queue.poll();
                if (chunk == null || !chunk.isLoaded()) continue;
                World world = chunk.getWorld();
                int lo = Math.max(minY, world.getMinHeight());
                int hi = Math.min(maxY, world.getMaxHeight() - 1);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = lo; y <= hi; y++) {
                            Block b = chunk.getBlock(x, y, z);
                            if (isFixture(b.getType())) darken(b, radius);
                        }
                    }
                }
            }
            if (queue.isEmpty() && scanTask != null) { scanTask.cancel(); scanTask = null; }
        }, 0L, 1L);

        if (seconds > 0) {
            endTask = plugin.getServer().getScheduler().runTaskLater(plugin, this::end, seconds * 20L);
        }
    }

    /** Restore every trapdoor and light block we touched, and clear the state. */
    public void end() {
        if (!active) return;
        active = false;
        if (scanTask != null) { scanTask.cancel(); scanTask = null; }
        if (endTask != null) { endTask.cancel(); endTask = null; }
        for (Map.Entry<Location, BlockData> e : undo.entrySet()) {
            Location loc = e.getKey();
            if (loc.getWorld() != null) loc.getBlock().setBlockData(e.getValue(), false);
        }
        undo.clear();
        broadcast("&e&lThe lights flicker back on.", Sound.BLOCK_BEACON_ACTIVATE);
    }

    public void toggle(int seconds) {
        if (active) end(); else start(seconds);
    }

    // --- internals ----------------------------------------------------------

    /** A light-fixture trapdoor (TOP, facing WEST/EAST): switch it unpowered and
     *  strip nearby light blocks, remembering both for restore. */
    private void darken(Block block, int radius) {
        if (!(block.getBlockData() instanceof TrapDoor td)) return;
        if (td.getHalf() != Bisected.Half.TOP) return;
        if (td.getFacing() != BlockFace.WEST && td.getFacing() != BlockFace.EAST) return;
        if (td.isPowered()) {
            undo.putIfAbsent(block.getLocation(), td.clone());
            td.setPowered(false);
            block.setBlockData(td, false);
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block near = block.getRelative(dx, dy, dz);
                    if (near.getType() == Material.LIGHT) {
                        undo.putIfAbsent(near.getLocation(), near.getBlockData().clone());
                        near.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private boolean isFixture(Material mat) {
        String name = mat.name();
        return name.startsWith("WAXED") && name.contains("COPPER_TRAPDOOR");
    }

    /** The chunks to sweep: a single configured world if set, else every world -
     *  only LOADED chunks, so we never force-load the map. */
    private List<Chunk> scanScope() {
        String worldName = plugin.getConfig().getString("blackout.world", "").trim();
        List<Chunk> chunks = new ArrayList<>();
        if (!worldName.isEmpty()) {
            World w = plugin.getServer().getWorld(worldName);
            if (w != null) chunks.addAll(List.of(w.getLoadedChunks()));
        } else {
            for (World w : plugin.getServer().getWorlds()) {
                chunks.addAll(List.of(w.getLoadedChunks()));
            }
        }
        return chunks;
    }

    private void broadcast(String legacy, Sound sound) {
        Component msg = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
            .legacyAmpersand().deserialize(legacy);
        for (var p : plugin.getServer().getOnlinePlayers()) {
            p.sendMessage(msg);
            p.playSound(p.getLocation(), sound, 1f, 0.8f);
        }
    }
}
