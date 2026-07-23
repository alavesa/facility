package fi.alavesa.facility;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A crosshair highlight: when the player looks at something they can RIGHT-CLICK
 * (a container, a door/button/lever, or an Interaction box like a stash/CCTV), a
 * small yellow corner-bracket frame is drawn around the crosshair. The frame is a
 * facility:crosshair font glyph lifted to screen-centre and sent on the action bar
 * only while hovering; when they look away it's cleared once. (Action-bar based, so
 * on a player who also has a live action-bar HUD it may briefly share that line.)
 */
public final class InteractCrosshair implements Runnable {

    private static final Component GLYPH = Component.text("")
        .font(Key.key("facility", "crosshair")).color(NamedTextColor.WHITE);

    private final FacilityPlugin plugin;
    private final Set<UUID> hovering = ConcurrentHashMap.newKeySet();

    public InteractCrosshair(FacilityPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            boolean now = looksAtInteractable(p);
            boolean was = hovering.contains(p.getUniqueId());
            if (now) {
                p.sendActionBar(GLYPH);
                hovering.add(p.getUniqueId());
            } else if (was) {
                p.sendActionBar(Component.empty());   // clear the highlight once
                hovering.remove(p.getUniqueId());
            }
        }
    }

    private boolean looksAtInteractable(Player p) {
        var e = p.rayTraceEntities(5);
        if (e != null && e.getHitEntity() instanceof Interaction) return true;
        var b = p.rayTraceBlocks(5.0);
        return b != null && b.getHitBlock() != null && isInteractable(b.getHitBlock());
    }

    private boolean isInteractable(Block block) {
        if (block.getState() instanceof InventoryHolder) return true;   // chest, barrel, furnace...
        String n = block.getType().name();
        return n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR") || n.endsWith("_BUTTON")
            || n.endsWith("_FENCE_GATE") || n.equals("LEVER") || n.equals("CRAFTING_TABLE")
            || n.equals("ENCHANTING_TABLE") || n.equals("ANVIL") || n.endsWith("_SIGN")
            || n.endsWith("_BED") || n.equals("BELL") || n.equals("RESPAWN_ANCHOR");
    }
}
