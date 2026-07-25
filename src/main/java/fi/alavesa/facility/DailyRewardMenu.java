package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The daily-reward screens. Players open the CLAIM screen (from the main-menu dialog's "Daily
 * Rewards" button): a chest GUI of day icons, top-left = day 1, with today's claimable day
 * glowing - click it to collect the item or currency and advance the streak. Ops open the EDITOR
 * with /facility dailyreward edit: the same grid, but they drop the reward ITEM for each day into
 * its slot and it saves on close (currency-per-day is set with /facility dailyreward currency).
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

    private enum Mode { CLAIM, EDIT }

    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Mode mode;
        private Holder(Mode mode) { this.mode = mode; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private int rows() { return Math.max(1, (store.days() + 8) / 9); }

    // --- claim screen -------------------------------------------------------

    public void openClaim(Player player) {
        int days = store.days();
        Holder holder = new Holder(Mode.CLAIM);
        Inventory inv = Bukkit.createInventory(holder, rows() * 9,
            Component.text("Daily Rewards", NamedTextColor.DARK_AQUA));
        holder.inventory = inv;
        int claimable = store.claimableDay(player.getUniqueId());
        int streak = store.streak(player.getUniqueId());
        for (int day = 1; day <= days; day++) {
            inv.setItem(day - 1, claimIcon(day, claimable, streak));
        }
        player.openInventory(inv);
    }

    private ItemStack claimIcon(int day, int claimable, int streak) {
        boolean isClaimable = day == claimable;
        ItemStack icon = rewardIcon(day);
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(line("Day " + day, NamedTextColor.GRAY));
        lore.add(rewardLine(day));
        if (isClaimable) {
            lore.add(Component.empty());
            lore.add(line("» CLICK TO CLAIM", NamedTextColor.GREEN));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else if (claimable == 0 && day == streak) {
            lore.add(line("✔ Claimed today", NamedTextColor.DARK_GREEN));
        } else if (day < claimable) {
            lore.add(line("✔ Claimed", NamedTextColor.DARK_GREEN));
        } else {
            lore.add(line("Locked", NamedTextColor.DARK_GRAY));
        }
        meta.itemName(Component.text("Day " + day, isClaimable ? NamedTextColor.GREEN : NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    /** The visual for a day: its item reward, a credit icon for currency, or a blank pane. */
    private ItemStack rewardIcon(int day) {
        ItemStack item = store.itemReward(day);
        if (item != null) return item.clone();
        if (store.currencyReward(day) > 0) return new ItemStack(Material.GOLD_NUGGET);
        return new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
    }

    private Component rewardLine(int day) {
        ItemStack item = store.itemReward(day);
        if (item != null) {
            return line("Reward: " + item.getAmount() + "x "
                + item.getType().name().toLowerCase().replace('_', ' '), NamedTextColor.AQUA);
        }
        int cur = store.currencyReward(day);
        if (cur > 0) return line("Reward: " + cur + " Credits", NamedTextColor.GOLD);
        return line("Reward: (not set)", NamedTextColor.DARK_GRAY);
    }

    private void claim(Player player, int day) {
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
        openClaim(player);   // refresh so it now reads as claimed
    }

    // --- editor screen ------------------------------------------------------

    public void openEditor(Player admin) {
        int days = store.days();
        Holder holder = new Holder(Mode.EDIT);
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

    // --- events -------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        if (holder.mode == Mode.EDIT) return;   // editor: let ops move items freely
        event.setCancelled(true);               // claim screen: icons are never picked up
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int day = event.getRawSlot() + 1;
        if (day < 1 || day > store.days()) return;
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
        claim(player, day);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        if (holder.mode != Mode.EDIT) return;
        Inventory inv = event.getView().getTopInventory();
        for (int day = 1; day <= store.days(); day++) {
            ItemStack slot = inv.getItem(day - 1);
            store.setItemReward(day, slot == null || slot.getType().isAir() ? null : slot.clone());
        }
        if (event.getPlayer() instanceof Player p) {
            Msg.actionbar(p, Component.text("Daily rewards saved.", NamedTextColor.GREEN));
        }
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
