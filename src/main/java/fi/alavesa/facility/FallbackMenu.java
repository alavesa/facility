package fi.alavesa.facility;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * The custom lobby menus. There is no external menu plugin anymore - these are
 * chest inventories dressed up entirely by our resource pack:
 *
 *   - The inventory TITLE carries a font glyph (facility:menu) that paints a
 *     full-screen background over the vanilla chest: a dark SITE-19 screen with
 *     full-width gray BUTTON BARS baked in (see tools/gen_menu.py). U+F801
 *     rewinds the cursor to the top-left; U+E000 is the main-menu panel and
 *     U+E001 the team-selector panel.
 *   - Each button bar is made clickable across its whole width by filling that
 *     row with INVISIBLE items (the facility:blank transparent model), tagged
 *     with the action they run. Only the painted bar shows; the item is unseen.
 *   - The team selector drops real team icons into the baked slot wells; a
 *     team's name rides its item (hover), public teams are joinable by all,
 *     private ones show a barrier unless the player has been granted access.
 *
 * If the resource pack isn't applied the menus still FUNCTION (the bars are
 * clickable, the icons are real) - they just fall back to the vanilla chest
 * look instead of the painted panel.
 */
public final class FallbackMenu implements Listener {

    private static final Key MENU_FONT = Key.key("facility", "menu");
    private static final String BG_MAIN = "\uF801\uE000";   // rewind + main panel
    private static final String BG_TEAMS = "\uF801\uE001";  // rewind + team panel

    // Chest rows the baked bars / wells live on (row r = slots r*9 .. r*9+8).
    private static final int PLAY_ROW = 1;
    private static final int TEAM_BTN_ROW = 3;
    private static final int[] TEAM_SLOTS = {10, 11, 12, 13, 14, 15, 16,   // wells row 1
                                             28, 29, 30, 31, 32, 33, 34};  // wells row 3

    private final FacilityPlugin plugin;
    private final TeamManager teams;
    private final NamespacedKey actionKey;

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
        this.actionKey = new NamespacedKey(plugin, "menu_action");
    }

    // --- main menu ----------------------------------------------------------

    public void open(Player player) {
        MainHolder holder = new MainHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, title(BG_MAIN));
        holder.inv = inv;

        // Whole PLAY bar clickable; whole SELECT TEAM bar clickable.
        for (int col = 0; col < 9; col++) {
            inv.setItem(PLAY_ROW * 9 + col, action("play", "&a&lPLAY"));
            inv.setItem(TEAM_BTN_ROW * 9 + col, action("teams", "&b&lSELECT TEAM"));
        }
        player.openInventory(inv);
    }

    // --- team selector ------------------------------------------------------

    public void openTeams(Player player) {
        TeamHolder holder = new TeamHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, title(BG_TEAMS));
        holder.inv = inv;

        int i = 0;
        for (Team team : teams.all()) {
            if (i >= TEAM_SLOTS.length) break;
            int slot = TEAM_SLOTS[i++];
            boolean allowed = teams.mayJoin(team, player);
            List<String> lore = new ArrayList<>();
            lore.add(team.privateTeam() ? "&8Private team" : "&8Public team");
            lore.add("&8Rank: &7" + team.rank());
            if (allowed) {
                lore.add("&aClick to deploy.");
                inv.setItem(slot, tagged(team.iconOr(Material.PAPER), team.display(), lore, team.id()));
            } else {
                lore.add("&cPrivate - ask an admin for access.");
                inv.setItem(slot, tagged(Material.BARRIER, "&7" + strip(team.display()), lore, null));
            }
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
            if (clicked == null || !clicked.hasItemMeta()) return;
            String action = clicked.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
            if ("play".equals(action)) {
                player.performCommand("facility continue");
            } else if ("teams".equals(action)) {
                openTeams(player);
            }
        } else if (holder instanceof TeamHolder) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            String id = clicked.getItemMeta().getPersistentDataContainer()
                .get(plugin.teamKey(), PersistentDataType.STRING);
            if (id != null) {
                player.performCommand("facility team " + id);
                player.closeInventory();
            }
        }
    }

    // --- item helpers -------------------------------------------------------

    /** The painted-panel title: a glyph in the facility:menu font, white so the
     *  bitmap keeps its own colours. */
    private Component title(String glyph) {
        return Component.text(glyph).font(MENU_FONT).color(NamedTextColor.WHITE);
    }

    /** An INVISIBLE, clickable button cell (transparent model) tagged with the
     *  action it runs. Fills a bar row so the whole bar is clickable. */
    private ItemStack action(String actionTag, String hoverName) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("facility", "blank"));   // transparent
        meta.displayName(legacy(hoverName).decoration(TextDecoration.ITALIC, false));
        meta.addItemFlags(ItemFlag.values());
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, actionTag);
        item.setItemMeta(meta);
        return item;
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
                PersistentDataType.STRING, teamId);
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
