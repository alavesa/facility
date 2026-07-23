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
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stashes: physical, ender-chest-style containers (a custom spawner-cage model). Each
 * placed stash is INDEPENDENT and PERSONAL - what you put in one stash lives only in
 * that stash, and every player sees their own contents in it. The stash opens as a
 * 27-slot grid; you start with 3 usable tiles in the CENTRE and buy the tiles AROUND
 * them, growing the storage outward (10, 20, 30 credits, +10 each). SCP items can't be
 * stored. Physical credit cash left in a stash is tallied into the stash-credit HUD.
 */
public final class StashManager implements Listener {

    public static final String TAG_STASH = "facility.stash";
    private static final int SIZE = 27;                 // 3 rows x 9 cols
    private static final int COLS = 9;
    /** The 3 free centre tiles (middle row, centre three columns). */
    private static final Set<Integer> CENTRE = Set.of(12, 13, 14);
    private static final String PERSONAL = "personal";

    private final FacilityPlugin plugin;
    private final File dir;
    private final NamespacedKey stashIdKey;
    private final NamespacedKey modelKey;

    public StashManager(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "stashes");
        if (!dir.exists()) dir.mkdirs();
        this.stashIdKey = new NamespacedKey(plugin, "stash_id");
        this.modelKey = new NamespacedKey(plugin, "stash_model");
    }

    /** GUI holder - whose stash, which one, and which tiles are unlocked. */
    private static final class Stash implements InventoryHolder {
        final UUID owner;
        final String stashId;
        final Set<Integer> unlocked;
        Inventory inv;
        Stash(UUID owner, String stashId, Set<Integer> unlocked) {
            this.owner = owner; this.stashId = stashId; this.unlocked = unlocked;
        }
        @Override public Inventory getInventory() { return inv; }
    }

    // ------------------------------------------------------------- open / render
    public void open(Player player, String stashId) {
        YamlConfiguration cfg = load(player.getUniqueId(), stashId);
        Set<Integer> unlocked = new HashSet<>(cfg.getIntegerList("unlocked"));
        if (unlocked.isEmpty()) unlocked.addAll(CENTRE);   // fresh stash: the 3 centre tiles
        Stash s = new Stash(player.getUniqueId(), stashId, unlocked);
        Inventory inv = Bukkit.createInventory(s, SIZE, Component.text("Stash", NamedTextColor.DARK_AQUA));
        s.inv = inv;
        for (int slot : unlocked) {
            ItemStack it = cfg.getItemStack("slot." + slot);
            if (it != null) inv.setItem(slot, it);
        }
        repaint(s);
        player.openInventory(inv);
    }

    /** Redraw the non-storage decoration: BUY buttons on tiles adjacent to unlocked
     *  ones, plain locked panes everywhere else. Unlocked tiles are left as storage. */
    private void repaint(Stash s) {
        int cost = tileCost(s);
        for (int i = 0; i < SIZE; i++) {
            if (s.unlocked.contains(i)) continue;          // storage tile - leave the item
            if (isBuyable(i, s.unlocked)) {
                s.inv.setItem(i, named(Material.GOLD_NUGGET,
                    Component.text("Buy this tile — " + cost + " credits", NamedTextColor.GOLD),
                    List.of(line("Click to unlock this storage tile."),
                        line("Each new tile costs 10 more."))));
            } else {
                s.inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE,
                    Component.text("Locked", NamedTextColor.DARK_GRAY),
                    List.of(line("Buy an adjacent tile first."))));
            }
        }
    }

    /** A tile is buyable if it's not unlocked but borders an unlocked tile (4-dir). */
    private boolean isBuyable(int slot, Set<Integer> unlocked) {
        if (unlocked.contains(slot)) return false;
        for (int n : neighbours(slot)) if (unlocked.contains(n)) return true;
        return false;
    }

    /** 4-directional neighbours of a slot within the grid (no row wrap). */
    private int[] neighbours(int slot) {
        int row = slot / COLS, col = slot % COLS;
        java.util.List<Integer> out = new java.util.ArrayList<>(4);
        if (row > 0) out.add(slot - COLS);
        if (row < SIZE / COLS - 1) out.add(slot + COLS);
        if (col > 0) out.add(slot - 1);
        if (col < COLS - 1) out.add(slot + 1);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Cost of the NEXT tile: 10 for the 4th tile, 20 for the 5th, ... (+10 each). */
    private int tileCost(Stash s) {
        return 10 * (s.unlocked.size() - CENTRE.size() + 1);
    }

    // ------------------------------------------------------------- clicks
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Stash s)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        boolean inStash = raw >= 0 && raw < SIZE;

        if (inStash && !s.unlocked.contains(raw)) {
            event.setCancelled(true);                       // can't use a non-unlocked tile
            if (isBuyable(raw, s.unlocked)) buyTile(s, player, raw);
            return;
        }
        // a click in an unlocked tile (or the player inventory) - let it through, but
        // sweep out any SCP next tick and re-tally credits (covers every click path)
        Bukkit.getScheduler().runTask(plugin, () -> { bounceScps(s, player); retally(player); });
    }

    private void buyTile(Stash s, Player player, int slot) {
        int cost = tileCost(s);
        // pay with the physical credit cash carried in the inventory (coins/bills), giving
        // change if a bigger bill has to be broken.
        if (!spendWallet(player, cost)) {
            player.sendActionBar(Component.text("Not enough credits (have " + wallet(player)
                + ", need " + cost + ").", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 0.7f, 0.9f);
            return;
        }
        s.unlocked.add(slot);
        s.inv.setItem(slot, null);                          // free the newly-unlocked tile
        repaint(s);
        saveMeta(s);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
        player.sendActionBar(Component.text("Tile unlocked. Wallet: " + wallet(player), NamedTextColor.GOLD));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Stash s)) return;
        for (int slot : event.getRawSlots()) {
            if (slot < SIZE && !s.unlocked.contains(slot)) { event.setCancelled(true); return; }
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

    /** Any SCP item in an unlocked tile is bounced back to the player. */
    private void bounceScps(Stash s, Player player) {
        for (int slot : s.unlocked) {
            ItemStack it = s.inv.getItem(slot);
            if (isScp(it)) {
                s.inv.setItem(slot, null);
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
        cfg.set("unlocked", new java.util.ArrayList<>(s.unlocked));
        for (int slot : s.unlocked) {
            ItemStack it = s.inv.getItem(slot);
            if (it != null && !isScp(it)) cfg.set("slot." + slot, it);
        }
        try { cfg.save(file(s.owner, s.stashId)); }
        catch (IOException e) { plugin.getLogger().warning("Could not save stash " + s.stashId); }
    }
    private void saveMeta(Stash s) {   // persist the unlocked set right after a purchase
        YamlConfiguration cfg = load(s.owner, s.stashId);
        cfg.set("unlocked", new java.util.ArrayList<>(s.unlocked));
        try { cfg.save(file(s.owner, s.stashId)); } catch (IOException ignored) { }
    }

    /** Total STASH CREDITS = credit cash across ALL the player's stashes -> the HUD. */
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
        setScore("credits_stash", player.getName(), total);
    }

    // ------------------------------------------------------------- placed stash
    public boolean place(Player player) {
        var ray = player.rayTraceBlocks(6.0);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlockFace() == null) return false;
        org.bukkit.block.Block spot = ray.getHitBlock().getRelative(ray.getHitBlockFace());
        if (!spot.getType().isAir() && !spot.isReplaceable()) return false;   // don't clobber a real block

        // A REAL, EMPTY spawner block: it shows the recognisable iron cage, but because it
        // has no spawn data it neither SPINS a mob nor ever spawns one. (A BlockDisplay of a
        // spawner draws no visible cage on the client, which is why it looked unplaced.) The
        // skeleton "inside" is a separate STILL skull display, so nothing ever rotates.
        spot.setType(Material.SPAWNER);
        Location at = spot.getLocation().add(0.5, 0.0, 0.5);
        String stashId = UUID.randomUUID().toString();

        at.getWorld().spawn(at.clone().add(0, 0.30, 0), org.bukkit.entity.ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.SKELETON_SKULL));
            d.addScoreboardTag(TAG_STASH);
            float sc = 0.5f;
            d.setTransformation(new org.bukkit.util.Transformation(
                new org.joml.Vector3f(0, 0, 0), new org.joml.Quaternionf(),
                new org.joml.Vector3f(sc, sc, sc), new org.joml.Quaternionf()));
            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
        });
        // Box sits ON the spawner block, one pixel (1/16) proud on every side, so you can
        // click anywhere on the block - not just the top edge where it used to poke out.
        at.getWorld().spawn(spot.getLocation().add(0.5, -0.0625, 0.5), Interaction.class, i -> {
            i.addScoreboardTag(TAG_STASH);
            i.setInteractionWidth(1.125f);
            i.setInteractionHeight(1.125f);
            i.setResponsive(true);          // so a left-click registers as an attack (op punch-remove)
            i.getPersistentDataContainer().set(stashIdKey, PersistentDataType.STRING, stashId);
            i.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, blockKey(spot));
        });
        return true;
    }

    private String blockKey(org.bukkit.block.Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }
    private org.bukkit.block.Block blockFromKey(String key) {
        try {
            String[] p = key.split(":");
            var w = Bukkit.getWorld(p[0]);
            return w == null ? null : w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (Exception e) { return null; }
    }

    /** Remove the stash the player is looking at (spawner block + skull + box). */
    private boolean removeAt(Entity hit) {
        if (hit == null || !hit.getScoreboardTags().contains(TAG_STASH)) return false;
        if (hit instanceof Interaction box) {
            org.bukkit.block.Block b = blockFromKey(box.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING));
            if (b != null && b.getType() == Material.SPAWNER) b.setType(Material.AIR);
        }
        Location at = hit.getLocation();
        for (Entity near : at.getWorld().getNearbyEntities(at, 1.2, 1.2, 1.2)) {
            if (near.getScoreboardTags().contains(TAG_STASH)) near.remove();
        }
        return true;
    }

    public boolean removeLooked(Player player) {
        var ray = player.rayTraceEntities(6);
        return ray != null && removeAt(ray.getHitEntity());
    }

    /** Op PUNCH (left-click ATTACK) on a stash removes it. Uses the attack event, which -
     *  unlike the arm-swing animation - fires ONLY on a left-click, never on the right-click
     *  that opens the stash. The stash Interaction is setResponsive(true) so it's attackable. */
    @EventHandler(ignoreCancelled = false)
    public void onPunch(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Interaction box)) return;
        if (!box.getScoreboardTags().contains(TAG_STASH)) return;
        Player p = event.getPlayer();
        if (!p.hasPermission("facility.admin")) { event.setCancelled(true); return; }
        event.setCancelled(true);
        removeAt(box);
        p.sendActionBar(Component.text("Stash removed.", NamedTextColor.GRAY));
        p.playSound(p.getLocation(), Sound.BLOCK_STONE_BREAK, 0.8f, 0.9f);
    }

    @EventHandler
    public void onUse(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Interaction box)) return;
        if (!box.getScoreboardTags().contains(TAG_STASH)) return;
        event.setCancelled(true);
        String stashId = box.getPersistentDataContainer().get(stashIdKey, PersistentDataType.STRING);
        open(event.getPlayer(), stashId == null ? PERSONAL : stashId);
        event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.7f, 1.1f);
    }

    public void openPersonal(Player player) { open(player, PERSONAL); }

    public void sweepOrphans() {
        for (var world : Bukkit.getWorlds()) {
            for (Interaction box : world.getEntitiesByClass(Interaction.class)) {
                if (!box.getScoreboardTags().contains(TAG_STASH)) continue;
                org.bukkit.block.Block b = blockFromKey(box.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING));
                if (b == null || b.getType() != Material.SPAWNER) box.remove();   // cage broken -> clean up orphan
            }
        }
    }

    // ------------------------------------------------------------- item checks
    public static int creditValue(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        var s = item.getItemMeta().getCustomModelDataComponent().getStrings();
        if (s.contains("lab_credit100")) return 100;
        if (s.contains("lab_credit10")) return 10;
        if (s.contains("lab_credit")) return 1;
        return 0;
    }

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

    /** Credits the player is physically carrying (coins/bills in the inventory). */
    public int wallet(Player p) {
        int total = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            int v = creditValue(it);
            if (v > 0) total += v * it.getAmount();
        }
        return total;
    }

    /** Spend {@code amount} of physical cash from the inventory, handing back change if a
     *  bigger bill had to be broken. false (nothing removed) when they can't afford it. */
    private boolean spendWallet(Player p, int amount) {
        if (amount <= 0) return true;
        if (wallet(p) < amount) return false;
        int removed = 0;
        // pass 1: take denominations without exceeding the amount (100 -> 10 -> 1)
        for (int denom : new int[]{100, 10, 1}) {
            ItemStack[] c = p.getInventory().getContents();
            for (int i = 0; i < c.length && removed < amount; i++) {
                if (creditValue(c[i]) != denom) continue;
                while (c[i].getAmount() > 0 && removed + denom <= amount) {
                    c[i].setAmount(c[i].getAmount() - 1);
                    removed += denom;
                }
                p.getInventory().setItem(i, c[i].getAmount() > 0 ? c[i] : null);
            }
        }
        // pass 2: break one bigger bill for the remainder
        if (removed < amount) {
            for (int denom : new int[]{10, 100, 1}) {
                ItemStack[] c = p.getInventory().getContents();
                for (int i = 0; i < c.length && removed < amount; i++) {
                    if (creditValue(c[i]) != denom) continue;
                    c[i].setAmount(c[i].getAmount() - 1);
                    p.getInventory().setItem(i, c[i].getAmount() > 0 ? c[i] : null);
                    removed += denom;
                }
                if (removed >= amount) break;
            }
        }
        if (removed > amount) giveChange(p, removed - amount);
        return true;
    }

    /** Hand the player {@code amount} credits back as coins/bills (100s, 10s, 1s). */
    private void giveChange(Player p, int amount) {
        int rem = Math.max(0, amount);
        while (rem >= 100) { addOrDrop(p, creditItem(Material.PAPER, "100 Credits",
            NamedTextColor.DARK_GREEN, "lab_credit100")); rem -= 100; }
        while (rem >= 10)  { addOrDrop(p, creditItem(Material.PAPER, "10 Credits",
            NamedTextColor.GREEN, "lab_credit10")); rem -= 10; }
        while (rem >= 1)   { addOrDrop(p, creditItem(Material.GOLD_NUGGET, "1 Credit",
            NamedTextColor.GOLD, "lab_credit")); rem -= 1; }
    }

    private void addOrDrop(Player p, ItemStack item) {
        p.getInventory().addItem(item).values()
            .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    /** A credit coin/bill matching Labra's - the pack renders it from the model string. */
    private ItemStack creditItem(Material mat, String name, NamedTextColor color, String model) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.itemName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        var cmd = m.getCustomModelDataComponent();
        cmd.setStrings(List.of(model));
        m.setCustomModelDataComponent(cmd);
        it.setItemMeta(m);
        return it;
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
