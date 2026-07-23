package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Stashes: physical, ender-chest-style containers (a custom spawner-cage model) that
 * store a player's valuable items. Each placed stash is INDEPENDENT and PERSONAL -
 * what you put in one stash lives only in that stash, and every player sees their own
 * contents in it. You start with 3 usable tiles per stash and buy more (10, 20, 30,
 * +10 each). SCP items can't be stored. Physical CREDIT cash you leave in a stash is
 * tallied into your "stash credits" HUD counter, shown under your wallet.
 */
public final class StashManager implements Listener {

    public static final String TAG_STASH = "facility.stash";
    private static final int SIZE = 27;          // 3 rows
    private static final int FREE_TILES = 3;
    private static final String PERSONAL = "personal";

    private final FacilityPlugin plugin;
    private final File dir;
    private final NamespacedKey stashIdKey;

    public StashManager(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "stashes");
        if (!dir.exists()) dir.mkdirs();
        this.stashIdKey = new NamespacedKey(plugin, "stash_id");
    }

    /** Our stash GUI holder - remembers whose stash and which one. */
    private static final class Stash implements InventoryHolder {
        final UUID owner;
        final String stashId;
        int unlocked;
        Inventory inv;
        Stash(UUID owner, String stashId, int unlocked) {
            this.owner = owner; this.stashId = stashId; this.unlocked = unlocked;
        }
        @Override public Inventory getInventory() { return inv; }
    }

    // ------------------------------------------------------------- open / render
    public void open(Player player, String stashId) {
        YamlConfiguration cfg = load(player.getUniqueId(), stashId);
        int unlocked = Math.max(FREE_TILES, Math.min(SIZE, cfg.getInt("unlocked", FREE_TILES)));
        Stash holder = new Stash(player.getUniqueId(), stashId, unlocked);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
            Component.text("Stash", NamedTextColor.DARK_AQUA));
        holder.inv = inv;
        for (int i = 0; i < unlocked; i++) {
            ItemStack it = cfg.getItemStack("slot." + i);
            if (it != null) inv.setItem(i, it);
        }
        paintLocked(holder);
        player.openInventory(inv);
    }

    /** Fill the locked area: the FIRST locked slot is a "buy for N" button, the rest
     *  are plain locked panes. Called on open and after each purchase. */
    private void paintLocked(Stash s) {
        for (int i = s.unlocked; i < SIZE; i++) {
            if (i == s.unlocked) {
                s.inv.setItem(i, named(Material.GOLD_NUGGET,
                    Component.text("Buy this tile - " + tileCost(s.unlocked) + " credits",
                        NamedTextColor.GOLD),
                    List.of(line("Click to unlock another storage tile."),
                        line("Cost rises by 10 credits per tile."))));
            } else {
                s.inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE,
                    Component.text("Locked tile", NamedTextColor.DARK_GRAY),
                    List.of(line("Buy the earlier tiles first."))));
            }
        }
    }

    /** Cost to unlock the next tile: 10 for the 4th, 20 for the 5th, ... (+10 each). */
    private int tileCost(int unlocked) {
        return 10 * (unlocked - FREE_TILES + 1);
    }

    // ------------------------------------------------------------- clicks
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Stash s)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        boolean inStash = raw >= 0 && raw < SIZE;

        // the buy button
        if (inStash && raw == s.unlocked && s.unlocked < SIZE) {
            event.setCancelled(true);
            int cost = tileCost(s.unlocked);
            if (!takeCredits(player, cost)) {
                player.sendActionBar(Component.text("Not enough credits (" + balance(player)
                    + "/" + cost + ").", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.7f, 0.9f);
                return;
            }
            s.unlocked++;
            s.inv.setItem(raw, null);              // free the newly-unlocked slot
            paintLocked(s);
            saveMeta(s);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
            player.sendActionBar(Component.text("Tile unlocked. Balance: " + balance(player),
                NamedTextColor.GOLD));
            return;
        }
        // locked tiles are inert
        if (inStash && raw > s.unlocked) { event.setCancelled(true); return; }
        if (inStash && raw >= s.unlocked) { event.setCancelled(true); return; }

        // block storing SCPs, then re-tally credits next tick (covers every click path)
        Bukkit.getScheduler().runTask(plugin, () -> { bounceScps(s, player); retally(player); });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Stash s)) return;
        for (int slot : event.getRawSlots()) {
            if (slot < SIZE && slot >= s.unlocked) { event.setCancelled(true); return; }
        }
        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> { bounceScps(s, player); retally(player); });
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Stash s)) return;
        if (event.getPlayer() instanceof Player p) bounceScps(s, p);
        save(s);
        if (event.getPlayer() instanceof Player p) retally(p);
    }

    /** Any SCP item sitting in a usable slot is bounced back to the player. */
    private void bounceScps(Stash s, Player player) {
        for (int i = 0; i < s.unlocked; i++) {
            ItemStack it = s.inv.getItem(i);
            if (isScp(it)) {
                s.inv.setItem(i, null);
                player.getInventory().addItem(it).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                player.sendActionBar(Component.text("SCP items can't be stashed.", NamedTextColor.RED));
            }
        }
    }

    // ------------------------------------------------------------- persistence
    private File file(UUID owner, String stashId) {
        File pdir = new File(dir, owner.toString());
        if (!pdir.exists()) pdir.mkdirs();
        return new File(pdir, sanitize(stashId) + ".yml");
    }
    private YamlConfiguration load(UUID owner, String stashId) {
        return YamlConfiguration.loadConfiguration(file(owner, stashId));
    }
    private void save(Stash s) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("unlocked", s.unlocked);
        for (int i = 0; i < s.unlocked; i++) {
            ItemStack it = s.inv.getItem(i);
            if (it != null && !isScp(it)) cfg.set("slot." + i, it);
        }
        try { cfg.save(file(s.owner, s.stashId)); }
        catch (IOException e) { plugin.getLogger().warning("Could not save stash " + s.stashId + " for " + s.owner); }
    }
    private void saveMeta(Stash s) {   // persist the unlocked count immediately after a purchase
        YamlConfiguration cfg = load(s.owner, s.stashId);
        cfg.set("unlocked", s.unlocked);
        try { cfg.save(file(s.owner, s.stashId)); } catch (IOException ignored) { }
    }

    /** Recompute a player's total STASH CREDITS = value of credit cash across ALL their
     *  stashes, and publish it to the credits_stash scoreboard (the HUD reads it). */
    public void retally(Player player) {
        File pdir = new File(dir, player.getUniqueId().toString());
        int total = 0;
        if (pdir.isDirectory()) {
            File[] files = pdir.listFiles((d, n) -> n.endsWith(".yml"));
            if (files != null) for (File f : files) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                for (String key : cfg.getKeys(true)) {
                    if (!key.startsWith("slot.")) continue;
                    ItemStack it = cfg.getItemStack(key);
                    total += creditValue(it) * (it == null ? 0 : it.getAmount());
                }
            }
        }
        setScore("credits_stash", player.getName(), total);   // shared with Labra's HUD
    }

    // ------------------------------------------------------------- placed stash
    public boolean place(Player player) {
        var ray = player.rayTraceBlocks(6.0);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlockFace() == null) return false;
        Location at = ray.getHitBlock().getRelative(ray.getHitBlockFace()).getLocation().add(0.5, 0.0, 0.5);
        String stashId = UUID.randomUUID().toString();   // this physical stash's own id
        BlockDisplay model = at.getWorld().spawn(at.clone().add(0, 0.05, 0), BlockDisplay.class, d -> {
            d.setBlock(Material.SPAWNER.createBlockData());
            d.addScoreboardTag(TAG_STASH);
            float sc = 0.9f;
            d.setTransformation(new org.bukkit.util.Transformation(
                new org.joml.Vector3f(-sc / 2f, 0f, -sc / 2f), new org.joml.Quaternionf(),
                new org.joml.Vector3f(sc, sc, sc), new org.joml.Quaternionf()));
        });
        at.getWorld().spawn(at.clone().add(0, 0.1, 0), Interaction.class, i -> {
            i.addScoreboardTag(TAG_STASH);
            i.setInteractionWidth(1.0f);
            i.setInteractionHeight(1.0f);
            i.getPersistentDataContainer().set(stashIdKey, PersistentDataType.STRING, stashId);
            // remember the model so orphan sweeps can pair them
            i.getPersistentDataContainer().set(new NamespacedKey(plugin, "stash_model"),
                PersistentDataType.STRING, model.getUniqueId().toString());
        });
        return true;
    }

    public boolean removeLooked(Player player) {
        var ray = player.rayTraceEntities(6);
        if (ray == null || ray.getHitEntity() == null
            || !ray.getHitEntity().getScoreboardTags().contains(TAG_STASH)) return false;
        Location at = ray.getHitEntity().getLocation();
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
        String stashId = box.getPersistentDataContainer().get(stashIdKey, PersistentDataType.STRING);
        if (stashId == null) stashId = PERSONAL;
        open(event.getPlayer(), stashId);
        event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.7f, 1.1f);
    }

    /** Open the player's command stash (a single personal one, id "personal"). */
    public void openPersonal(Player player) { open(player, PERSONAL); }

    public void sweepOrphans() {
        NamespacedKey modelKey = new NamespacedKey(plugin, "stash_model");
        for (var world : Bukkit.getWorlds()) {
            for (Interaction box : world.getEntitiesByClass(Interaction.class)) {
                if (!box.getScoreboardTags().contains(TAG_STASH)) continue;
                String modelId = box.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
                if (modelId == null || Bukkit.getEntity(UUID.fromString(modelId)) == null) box.remove();
            }
        }
    }

    // ------------------------------------------------------------- item checks
    /** The credit value of one of this item (cash), or 0. Matches Labra's credit items. */
    public static int creditValue(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        var s = item.getItemMeta().getCustomModelDataComponent().getStrings();
        if (s.contains("lab_credit100")) return 100;
        if (s.contains("lab_credit10")) return 10;
        if (s.contains("lab_credit")) return 1;
        return 0;
    }

    /** Heuristic: an SCP item (blocked from stashes). Matches a custom_model_data or a
     *  display name that mentions an SCP. */
    public static boolean isScp(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        for (String str : meta.getCustomModelDataComponent().getStrings()) {
            if (str.toLowerCase().contains("scp")) return true;
        }
        if (meta.hasItemName()) {
            String n = PlainTextComponentSerializer.plainText().serialize(meta.itemName()).toUpperCase();
            if (n.contains("SCP-") || n.contains("SCP ")) return true;
        }
        if (meta.hasDisplayName()) {
            String n = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toUpperCase();
            if (n.contains("SCP-") || n.contains("SCP ")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- helpers
    private ItemStack named(Material mat, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.itemName(name.decoration(TextDecoration.ITALIC, false));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }
    private Component line(String text) {
        return Component.text(text, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false);
    }
    private String sanitize(String id) { return id.replaceAll("[^A-Za-z0-9_-]", "_"); }

    // credit scoreboards (shared with Labra's Credits: "credits" / "credits_stash")
    public int balance(Player p) { return score("credits", p.getName()); }
    private boolean takeCredits(Player p, int amount) {
        int b = balance(p);
        if (b < amount) return false;
        setScore("credits", p.getName(), b - amount);
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
