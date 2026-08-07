package fi.alavesa.facility;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Breakable blocks: any block inside an area flagged breakable (/facility area breakable &lt;name&gt; on)
 * can be destroyed - by C4/grenades/TNT and any other explosion, or by simply breaking it - and then
 * RESPAWNS itself after a delay (breakable.respawn-seconds, default 60). Blocks OUTSIDE breakable
 * areas are protected from explosions, so a blast only chews through the destructible zones.
 */
public final class BreakableManager implements Listener {

    private final FacilityPlugin plugin;
    private final AreaManager areas;
    private final Set<String> pending = new HashSet<>();   // block keys with a restore already queued

    public BreakableManager(FacilityPlugin plugin, AreaManager areas) {
        this.plugin = plugin;
        this.areas = areas;
    }

    private long respawnTicks() {
        return Math.max(1, plugin.getConfig().getInt("breakable.respawn-seconds", 60)) * 20L;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) { handleBlast(event.blockList()); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) { handleBlast(event.blockList()); }

    /** Only breakable-area blocks are consumed by a blast (and queued to respawn); everything else is
     *  removed from the blast list so the facility itself is never damaged. */
    private void handleBlast(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (areas.isBreakable(b.getLocation())) scheduleRespawn(b);
            else it.remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (areas.isBreakable(b.getLocation())) scheduleRespawn(b);   // broken by any method -> it heals back
    }

    private void scheduleRespawn(Block b) {
        if (b.getType() == Material.AIR) return;
        Location loc = b.getLocation();
        String key = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        if (!pending.add(key)) return;   // already queued
        BlockData data = b.getBlockData();
        loc.getWorld().spawnParticle(Particle.BLOCK, loc.clone().add(0.5, 0.5, 0.5), 24, 0.3, 0.3, 0.3, data);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pending.remove(key);
            Block cur = loc.getBlock();
            if (cur.getType() == Material.AIR) {
                cur.setBlockData(data);
                loc.getWorld().playSound(loc, Sound.BLOCK_STONE_PLACE, 0.8f, 1.1f);
            }
        }, respawnTicks());
    }
}
