package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Personal STASH: a private 54-slot vault where a player keeps their most valuable
 * items, plus a separate credit balance held "in the stash". Opened with /stash, or
 * by right-clicking a placed stash - a custom spawner-cage model (a BlockDisplay of a
 * SPAWNER) that any player can use to reach THEIR OWN stash. Contents persist to
 * data/stashes/&lt;uuid&gt;.yml; stash credits live on the shared "credits_stash"
 * scoreboard (Labra's Credits reads the same one), spendable balance on "credits".
 */
public final class StashManager implements Listener {

    public static final String TAG_STASH = "facility.stash";
    private static final int SIZE = 54;

    private final FacilityPlugin plugin;
    private final File dir;
    private final NamespacedKey stashKey;

    public StashManager(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "stashes");
        if (!dir.exists()) dir.mkdirs();
        this.stashKey = new NamespacedKey(plugin, "stash");
    }

    /** Marks our stash inventory so clicks/closes are ours; remembers the owner. */
    private static final class Stash implements InventoryHolder {
        final UUID owner;
        Inventory inv;
        Stash(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ------------------------------------------------------------- open / save
    public void open(Player player) {
        Stash holder = new Stash(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, SIZE,
            Component.text("Your Stash", NamedTextColor.DARK_AQUA));
        holder.inv = inv;
        YamlConfiguration cfg = load(player.getUniqueId());
        for (int i = 0; i < SIZE; i++) {
            ItemStack it = cfg.getItemStack("slot." + i);
            if (it != null) inv.setItem(i, it);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Stash stash)) return;
        YamlConfiguration cfg = new YamlConfiguration();
        for (int i = 0; i < SIZE; i++) {
            ItemStack it = event.getInventory().getItem(i);
            if (it != null) cfg.set("slot." + i, it);
        }
        try { cfg.save(new File(dir, stash.owner + ".yml")); }
        catch (IOException e) { plugin.getLogger().warning("Could not save stash for " + stash.owner); }
    }

    private YamlConfiguration load(UUID id) {
        return YamlConfiguration.loadConfiguration(new File(dir, id + ".yml"));
    }

    // ------------------------------------------------------------- placed stash
    /** Op places a stash at the block they're looking at: a spawner-cage model + an
     *  interaction box that opens the clicker's own stash. */
    public boolean place(Player player) {
        var ray = player.rayTraceBlocks(6.0);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlockFace() == null) return false;
        Location at = ray.getHitBlock().getRelative(ray.getHitBlockFace()).getLocation().add(0.5, 0.0, 0.5);
        BlockDisplay model = at.getWorld().spawn(at.clone().add(0, 0.05, 0), BlockDisplay.class, d -> {
            d.setBlock(Material.SPAWNER.createBlockData());
            d.addScoreboardTag(TAG_STASH);
            float s = 0.9f;
            d.setTransformation(new org.bukkit.util.Transformation(
                new org.joml.Vector3f(-s / 2f, 0f, -s / 2f), new org.joml.Quaternionf(),
                new org.joml.Vector3f(s, s, s), new org.joml.Quaternionf()));
        });
        at.getWorld().spawn(at.clone().add(0, 0.1, 0), Interaction.class, i -> {
            i.addScoreboardTag(TAG_STASH);
            i.setInteractionWidth(1.0f);
            i.setInteractionHeight(1.0f);
            i.getPersistentDataContainer().set(stashKey, org.bukkit.persistence.PersistentDataType.STRING,
                model.getUniqueId().toString());
        });
        return true;
    }

    public boolean removeLooked(Player player) {
        var ray = player.rayTraceEntities(6);
        if (ray == null || !(ray.getHitEntity() instanceof Entity e)
            || !e.getScoreboardTags().contains(TAG_STASH)) return false;
        Location at = e.getLocation();
        for (Entity near : at.getWorld().getNearbyEntities(at, 1.2, 1.2, 1.2)) {
            if (near.getScoreboardTags().contains(TAG_STASH)) near.remove();
        }
        return true;
    }

    @EventHandler
    public void onUse(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Interaction box)) return;
        if (!box.getScoreboardTags().contains(TAG_STASH)) return;
        event.setCancelled(true);
        open(event.getPlayer());
        event.getPlayer().playSound(event.getPlayer().getLocation(),
            org.bukkit.Sound.BLOCK_ENDER_CHEST_OPEN, 0.7f, 1.1f);
    }

    /** Remove orphaned stash parts (interaction with no model) - housekeeping. */
    public void sweepOrphans() {
        for (var world : Bukkit.getWorlds()) {
            for (Interaction box : world.getEntitiesByClass(Interaction.class)) {
                if (!box.getScoreboardTags().contains(TAG_STASH)) continue;
                String modelId = box.getPersistentDataContainer().get(stashKey,
                    org.bukkit.persistence.PersistentDataType.STRING);
                if (modelId == null || Bukkit.getEntity(UUID.fromString(modelId)) == null) box.remove();
            }
        }
    }

    // ------------------------------------------------------------- stash credits
    public int stashCredits(Player p) { return score("credits_stash", p.getName()); }
    public int balance(Player p) { return score("credits", p.getName()); }

    /** Move credits from the spendable balance INTO the stash. */
    public boolean deposit(Player p, int amount) {
        if (amount <= 0) return false;
        int bal = balance(p);
        if (bal < amount) return false;
        setScore("credits", p.getName(), bal - amount);
        setScore("credits_stash", p.getName(), stashCredits(p) + amount);
        return true;
    }

    /** Move credits from the stash back to the spendable balance. */
    public boolean withdraw(Player p, int amount) {
        if (amount <= 0) return false;
        int st = stashCredits(p);
        if (st < amount) return false;
        setScore("credits_stash", p.getName(), st - amount);
        setScore("credits", p.getName(), balance(p) + amount);
        return true;
    }

    private Objective obj(String id) {
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective o = board.getObjective(id);
        if (o == null) {
            try { o = board.registerNewObjective(id, Criteria.DUMMY, Component.text(id)); }
            catch (IllegalArgumentException e) { o = board.getObjective(id); }
        }
        return o;
    }
    private int score(String id, String name) {
        Objective o = obj(id);
        if (o == null) return 0;
        var s = o.getScore(name);
        return s.isScoreSet() ? s.getScore() : 0;
    }
    private void setScore(String id, String name, int v) {
        Objective o = obj(id);
        if (o != null) o.getScore(name).setScore(Math.max(0, v));
    }
}
