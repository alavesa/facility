package fi.alavesa.facility;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;

/**
 * The inventory UI hub (Settings / Gun Stats / Player List menus and their hotbar buttons) was REMOVED
 * (0.18.0). What is left here: the recipe book stays empty, and any leftover UI button item from the
 * old builds is purged from inventories and cursors so nothing can ever open those menus again.
 */
public final class UiCleanup implements Listener {

    private final NamespacedKey buttonKey;   // BYTE: marked the old UI buttons

    public UiCleanup(FacilityPlugin plugin) {
        this.buttonKey = new NamespacedKey(plugin, "ui_button");
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : plugin.getServer().getOnlinePlayers()) purgeButtons(p);
        }, 20L, 40L);
    }

    @EventHandler public void onDiscover(PlayerRecipeDiscoverEvent event) { event.setCancelled(true); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        p.undiscoverRecipes(new ArrayList<>(p.getDiscoveredRecipes()));
        purgeButtons(p);
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(Bukkit.getPluginManager().getPlugin("Facility"), () -> purgeButtons(event.getPlayer()), 2L);
    }

    private void purgeButtons(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isButton(contents[i])) p.getInventory().setItem(i, null);
        }
        if (isButton(p.getItemOnCursor())) { p.setItemOnCursor(null); p.updateInventory(); }
    }

    private boolean isButton(ItemStack it) {
        return it != null && it.hasItemMeta()
            && it.getItemMeta().getPersistentDataContainer().has(buttonKey, PersistentDataType.BYTE);
    }
}
