package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The self-sufficient fallback GUI. When DeluxeMenus isn't installed the
 * plugin still needs a working main menu and team selector, so these are plain
 * chest inventories with the same two actions the DeluxeMenus buttons run:
 * Continue ({@code /facility continue}) and Select Team ({@code /facility teams}).
 *
 * Team selector: public teams are clickable by all; private teams show greyed
 * (barrier) and locked unless the player has been granted access.
 */
public final class FallbackMenu implements Listener {

    private final FacilityPlugin plugin;
    private final TeamManager teams;

    private static final class MainHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static final class TeamHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public FallbackMenu(FacilityPlugin plugin, TeamManager teams) {
        this.plugin = plugin;
        this.teams = teams;
    }

    // --- main menu ----------------------------------------------------------

    public void open(Player player) {
        MainHolder holder = new MainHolder();
        Inventory inv = Bukkit.createInventory(holder, 54,
            Component.text("SITE-19 // MAIN MENU", NamedTextColor.DARK_AQUA));
        holder.inv = inv;

        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        inv.setItem(20, button(Material.LIME_CONCRETE, "&aContinue",
            List.of("&7Enter the facility.", "&7Return to where you last stood.")));
        inv.setItem(24, button(Material.NETHERITE_HELMET, "&bSelect Team",
            List.of("&7Choose your allegiance.")));
        inv.setItem(49, button(Material.PAPER, "&fSITE-19 Terminal",
            List.of("&8Player: &7" + player.getName(),
                "&8Status: &7Awaiting entry")));

        player.openInventory(inv);
    }

    // --- team selector ------------------------------------------------------

    public void openTeams(Player player) {
        TeamHolder holder = new TeamHolder();
        int rows = Math.max(1, (teams.all().size() + 8) / 9);
        Inventory inv = Bukkit.createInventory(holder, Math.min(54, rows * 9),
            Component.text("SITE-19 // TEAM SELECT", NamedTextColor.DARK_AQUA));
        holder.inv = inv;

        int slot = 0;
        for (Team team : teams.all()) {
            boolean allowed = teams.mayJoin(team, player);
            List<String> lore = new ArrayList<>();
            lore.add(team.privateTeam() ? "&8Private team" : "&8Public team");
            lore.add("&8Rank: &7" + team.rank());
            if (allowed) {
                lore.add("&aClick to join.");
                inv.setItem(slot++, tagged(team.iconOr(Material.PAPER), team.display(), lore, team.id()));
            } else {
                lore.add("&cPrivate - ask an admin for access.");
                inv.setItem(slot++, tagged(Material.BARRIER, "&7" + strip(team.display()), lore, null));
            }
            if (slot >= inv.getSize()) break;
        }
        player.openInventory(inv);
    }

    // --- click handling -----------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();

        if (holder instanceof MainHolder) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) return;
            if (clicked.getType() == Material.LIME_CONCRETE) {
                player.performCommand("facility continue");
            } else if (clicked.getType() == Material.NETHERITE_HELMET) {
                openTeams(player);
            }
        } else if (holder instanceof TeamHolder) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            String id = clicked.getItemMeta().getPersistentDataContainer()
                .get(plugin.teamKey(), org.bukkit.persistence.PersistentDataType.STRING);
            if (id != null) {
                player.performCommand("facility team " + id);
                player.closeInventory();
            }
        }
    }

    // --- item helpers -------------------------------------------------------

    private ItemStack pane(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String legacyName, List<String> legacyLore) {
        return tagged(material, legacyName, legacyLore, null);
    }

    private ItemStack tagged(Material material, String legacyName, List<String> legacyLore, String teamId) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(legacy(legacyName).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : legacyLore) lore.add(legacy(line).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (teamId != null) {
            meta.getPersistentDataContainer().set(plugin.teamKey(),
                org.bukkit.persistence.PersistentDataType.STRING, teamId);
        }
        item.setItemMeta(meta);
        return item;
    }

    private Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private String strip(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().serialize(
            LegacyComponentSerializer.legacyAmpersand().deserialize(legacy)).replaceAll("&.", "");
    }
}
