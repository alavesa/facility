package fi.alavesa.facility;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The daily-reward config + per-player claim state (dailyrewards.yml). A cycle is N days
 * (top-left = day 1 in the editor GUI); each day's reward is either an ItemStack or a lump of
 * currency. Per player we remember the last day they claimed (as an epoch day) and how far into
 * the cycle their streak has reached, so claiming advances the streak, a missed day resets it,
 * and a second claim on the same day is refused.
 */
public final class DailyRewardStore {

    private final FacilityPlugin plugin;
    private final File file;
    private final YamlConfiguration yaml;

    public DailyRewardStore(FacilityPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "dailyrewards.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    /** Today as an epoch day number (server local date). */
    public static long today() { return LocalDate.now().toEpochDay(); }

    // --- config -------------------------------------------------------------

    public int days() { return Math.max(1, Math.min(54, yaml.getInt("days", 7))); }

    public void setDays(int n) { yaml.set("days", Math.max(1, Math.min(54, n))); save(); }

    /** The item reward for a day (1-based), or null if that day pays currency / nothing. */
    public ItemStack itemReward(int day) { return yaml.getItemStack("rewards." + day + ".item"); }

    public void setItemReward(int day, ItemStack item) {
        yaml.set("rewards." + day + ".item", item);
        if (item != null) yaml.set("rewards." + day + ".currency", null); // one kind per day
        save();
    }

    /** The currency reward for a day (1-based), or 0 if none. */
    public int currencyReward(int day) { return Math.max(0, yaml.getInt("rewards." + day + ".currency", 0)); }

    public void setCurrency(int day, int amount) {
        yaml.set("rewards." + day + ".currency", Math.max(0, amount));
        if (amount > 0) yaml.set("rewards." + day + ".item", null);
        save();
    }

    public boolean hasReward(int day) { return itemReward(day) != null || currencyReward(day) > 0; }

    // --- per-player claim state --------------------------------------------

    public long lastClaim(UUID id) { return yaml.getLong("players." + id + ".last", -1); }

    public int streak(UUID id) { return yaml.getInt("players." + id + ".streak", 0); }

    /**
     * The day (1..N) this player can claim RIGHT NOW, or 0 if they already claimed today.
     * Claimed yesterday -> the streak continues (wrapping at the cycle length); a gap (or a
     * first-ever claim) resets to day 1.
     */
    public int claimableDay(UUID id) {
        long last = lastClaim(id);
        long today = today();
        if (last == today) return 0;                       // already claimed today
        if (last == today - 1) return (streak(id) % days()) + 1; // continue the streak
        return 1;                                          // reset
    }

    /** Record a claim of {@code day} today. */
    public void recordClaim(UUID id, int day) {
        yaml.set("players." + id + ".streak", day);
        yaml.set("players." + id + ".last", today());
        save();
    }

    private void save() {
        try { yaml.save(file); }
        catch (IOException e) { plugin.getLogger().severe("Could not save dailyrewards.yml: " + e.getMessage()); }
    }
}
