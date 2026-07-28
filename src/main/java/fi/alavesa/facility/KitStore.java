package fi.alavesa.facility;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.Base64;

/**
 * Per-team STARTER KITS: the loadout a player is handed when they deploy onto a team - a full
 * player inventory (36 main slots) plus the four armour pieces and the off-hand. Ops build each
 * team's kit in the {@link KitEditor} GUI; it's applied on join and on every respawn.
 *
 * Items are stored component-complete (Base64 of {@link ItemStack#serializeAsBytes()}) so guns,
 * vests, the wrench - anything from any plugin - survive the round-trip, the same way daily rewards do.
 */
public final class KitStore {

    public static final int MAIN_SLOTS = 36;   // 0-8 hotbar, 9-35 storage

    private final FacilityPlugin plugin;
    private final File file;
    private YamlConfiguration yaml;

    public KitStore(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kits.yml");
        reload();
    }

    public void reload() {
        yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    /** True if this team has any kit item defined (so we don't wipe inventories for teams with no kit). */
    public boolean hasKit(String teamId) {
        if (teamId == null) return false;
        ConfigurationSection sec = yaml.getConfigurationSection("kits." + teamId.toLowerCase());
        if (sec == null) return false;
        ConfigurationSection main = sec.getConfigurationSection("main");
        if (main != null && !main.getKeys(false).isEmpty()) return true;
        return sec.contains("armor.helmet") || sec.contains("armor.chestplate")
            || sec.contains("armor.leggings") || sec.contains("armor.boots") || sec.contains("offhand");
    }

    /** Replace the player's inventory + armour with the team's kit (no-op if the team has no kit). */
    public void apply(Player player, String teamId) {
        if (teamId == null || !hasKit(teamId)) return;
        String base = "kits." + teamId.toLowerCase();
        PlayerInventory inv = player.getInventory();
        inv.clear();   // main + armour + off-hand
        ConfigurationSection main = yaml.getConfigurationSection(base + ".main");
        if (main != null) {
            for (String key : main.getKeys(false)) {
                int slot;
                try { slot = Integer.parseInt(key); } catch (NumberFormatException e) { continue; }
                ItemStack it = decode(main.getString(key));
                if (it != null && slot >= 0 && slot < MAIN_SLOTS) inv.setItem(slot, it);
            }
        }
        inv.setHelmet(decode(yaml.getString(base + ".armor.helmet")));
        inv.setChestplate(decode(yaml.getString(base + ".armor.chestplate")));
        inv.setLeggings(decode(yaml.getString(base + ".armor.leggings")));
        inv.setBoots(decode(yaml.getString(base + ".armor.boots")));
        inv.setItemInOffHand(decode(yaml.getString(base + ".offhand")));
        player.updateInventory();
    }

    /** Save a kit from the editor's slot arrays. main is length {@link #MAIN_SLOTS}; any slot may be null. */
    public void save(String teamId, ItemStack[] main, ItemStack helmet, ItemStack chest,
                     ItemStack legs, ItemStack boots, ItemStack offhand) {
        String base = "kits." + teamId.toLowerCase();
        yaml.set(base, null);   // wipe the old kit first
        for (int i = 0; i < MAIN_SLOTS && i < main.length; i++) {
            if (notEmpty(main[i])) yaml.set(base + ".main." + i, encode(main[i]));
        }
        if (notEmpty(helmet)) yaml.set(base + ".armor.helmet", encode(helmet));
        if (notEmpty(chest)) yaml.set(base + ".armor.chestplate", encode(chest));
        if (notEmpty(legs)) yaml.set(base + ".armor.leggings", encode(legs));
        if (notEmpty(boots)) yaml.set(base + ".armor.boots", encode(boots));
        if (notEmpty(offhand)) yaml.set(base + ".offhand", encode(offhand));
        persist();
    }

    /** Load a team's kit into a 36-length main array + armour, for the editor to display. Index 0 of
     *  the returned armour array is helmet, then chestplate, leggings, boots, offhand. */
    public ItemStack[] mainOf(String teamId) {
        ItemStack[] main = new ItemStack[MAIN_SLOTS];
        ConfigurationSection sec = yaml.getConfigurationSection("kits." + teamId.toLowerCase() + ".main");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    if (slot >= 0 && slot < MAIN_SLOTS) main[slot] = decode(sec.getString(key));
                } catch (NumberFormatException ignored) { }
            }
        }
        return main;
    }

    public ItemStack armorOf(String teamId, String piece) {
        return decode(yaml.getString("kits." + teamId.toLowerCase() + ".armor." + piece));
    }

    public ItemStack offhandOf(String teamId) {
        return decode(yaml.getString("kits." + teamId.toLowerCase() + ".offhand"));
    }

    // -------------------------------------------------------------- internals

    private boolean notEmpty(ItemStack it) { return it != null && !it.getType().isAir(); }

    private void persist() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save kits.yml: " + e.getMessage());
        }
    }

    private static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static ItemStack decode(String data) {
        if (data == null || data.isEmpty()) return null;
        try { return ItemStack.deserializeBytes(Base64.getDecoder().decode(data)); }
        catch (Throwable t) { return null; }
    }
}
