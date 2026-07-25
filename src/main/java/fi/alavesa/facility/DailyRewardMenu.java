package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Daily rewards. The player-facing CLAIM screen is a native dialog (see {@link DialogMenu}); this
 * class keeps the admin EDITOR - a chest GUI where an op drops the reward ITEM for each day into
 * its slot (top-left = day 1) and it saves on close - plus the shared claim logic the dialog calls.
 */
public final class DailyRewardMenu implements Listener {

    private final FacilityPlugin plugin;
    private final DailyRewardStore store;
    private final StashManager stashes;

    public DailyRewardMenu(FacilityPlugin plugin, DailyRewardStore store, StashManager stashes) {
        this.plugin = plugin;
        this.store = store;
        this.stashes = stashes;
    }

    /** Marker for the editor chest so its close-save only fires for our inventory. */
    private static final class EditorHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private int rows() { return Math.max(1, (store.days() + 8) / 9); }

    // --- admin editor -------------------------------------------------------

    public void openEditor(Player admin) {
        int days = store.days();
        EditorHolder holder = new EditorHolder();
        Inventory inv = Bukkit.createInventory(holder, rows() * 9,
            Component.text("Daily Rewards — Editor", NamedTextColor.DARK_RED));
        holder.inventory = inv;
        for (int day = 1; day <= days; day++) {
            ItemStack item = store.itemReward(day);
            if (item != null) inv.setItem(day - 1, item.clone());
        }
        admin.openInventory(inv);
        Msg.actionbar(admin, Component.text("Drop a reward item in each day slot (top-left = day 1). "
            + "Currency days: /facility dailyreward currency <day> <amount>. Closes to save.",
            NamedTextColor.AQUA));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EditorHolder)) return;
        Inventory inv = event.getView().getTopInventory();
        for (int day = 1; day <= store.days(); day++) {
            ItemStack slot = inv.getItem(day - 1);
            store.setItemReward(day, slot == null || slot.getType().isAir() ? null : slot.clone());
        }
        if (event.getPlayer() instanceof Player p) {
            Msg.actionbar(p, Component.text("Daily rewards saved.", NamedTextColor.GREEN));
        }
    }

    // --- claim (called by the dialog) --------------------------------------

    /** Try to claim a day. Handles the streak rules, grants the reward, gives feedback. */
    public void claim(Player player, int day) {
        int claimable = store.claimableDay(player.getUniqueId());
        if (claimable == 0) {
            Msg.actionbar(player, Component.text("Already claimed today — come back tomorrow.",
                NamedTextColor.YELLOW));
            return;
        }
        if (day != claimable) {
            Msg.actionbar(player, Component.text("You can only claim day " + claimable + " right now.",
                NamedTextColor.YELLOW));
            return;
        }
        boolean gave = false;
        ItemStack item = store.itemReward(day);
        if (item != null) {
            player.getInventory().addItem(item.clone()).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            gave = true;
        }
        int cur = store.currencyReward(day);
        if (cur > 0) { stashes.giveCredits(player, cur); gave = true; }
        if (!gave) {
            Msg.actionbar(player, Component.text("Day " + day + " has no reward set yet.", NamedTextColor.YELLOW));
            return;
        }
        store.recordClaim(player.getUniqueId(), day);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        Msg.actionbar(player, Component.text("Day " + day + " reward claimed!", NamedTextColor.GREEN));
    }
}
