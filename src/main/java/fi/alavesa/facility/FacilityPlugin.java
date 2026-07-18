package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Facility - the SITE-19 lobby brain. New arrivals are frozen in spectator at
 * a menu vantage until they press Play; the custom menu (our own chest GUIs in
 * {@link FallbackMenu}) lets them pick a team and drops them into the world. A
 * combat-log system tags PvP for {@value CombatLogListener#COMBAT_LOG_SECONDS}
 * seconds and turns a tagged logout into a death.
 *
 * The menu buttons invoke this plugin's own commands, so every menu action has
 * a backing command here. The menu is a REJOIN-only thing (plus {@code /menu}
 * by hand); dying never sends you there. LuckPerms ranks are set via the
 * console `lp user ... parent set ...` fallback (see TeamManager) - no LuckPerms
 * maven dependency, robust whether or not it's loaded.
 */
public final class FacilityPlugin extends JavaPlugin {

    private PlayerStore store;
    private TeamManager teams;
    private LobbyManager lobby;
    private FallbackMenu fallback;
    private CombatLogListener combat;

    private NamespacedKey teamKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        teamKey = new NamespacedKey(this, "team_id");

        store = new PlayerStore(this);
        teams = new TeamManager(this);
        teams.load();

        fallback = new FallbackMenu(this, teams);
        lobby = new LobbyManager(this, store, fallback);
        combat = new CombatLogListener(this, store);

        getServer().getPluginManager().registerEvents(fallback, this);
        getServer().getPluginManager().registerEvents(lobby, this);
        getServer().getPluginManager().registerEvents(combat, this);
        // The countdown bar ticks every 5 ticks (4 Hz) for smooth drain.
        getServer().getScheduler().runTaskTimer(this, combat, 20L, 5L);

        getLogger().info("==================================");
        getLogger().info("  SITE-19 FACILITY // ONLINE");
        getLogger().info("  Teams loaded: " + teams.ids());
        getLogger().info("  DeluxeMenus: "
            + (Bukkit.getPluginManager().getPlugin("DeluxeMenus") != null ? "present" : "absent (built-in fallback)"));
        getLogger().info("  LuckPerms:   "
            + (Bukkit.getPluginManager().getPlugin("LuckPerms") != null ? "present" : "absent (rank grants will no-op)"));
        getLogger().info("==================================");
    }

    @Override
    public void onDisable() {
        if (combat != null) combat.shutdown();
        // TextDisplay holograms are entities and despawn/expire on their own;
        // nothing persistent to clean beyond the boss bars above.
        getLogger().info("SITE-19 FACILITY // offline");
    }

    public NamespacedKey teamKey() {
        return teamKey;
    }

    // --- commands -----------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /menu - return to the main menu by hand (the only OTHER way to reach
        // the menu besides rejoining the server).
        if (command.getName().equalsIgnoreCase("menu")) {
            if (!(sender instanceof Player player)) return error(sender, "Players only.");
            if (!player.hasPermission("facility.use")) return error(sender, "No permission.");
            lobby.returnToMenu(player);
            return true;
        }
        if (args.length == 0) return usage(sender);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "continue" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (!player.hasPermission("facility.use")) return error(sender, "No permission.");
                lobby.continueInto(player);
                return true;
            }
            case "teams", "selector" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (!player.hasPermission("facility.use")) return error(sender, "No permission.");
                openTeams(player);
                return true;
            }
            case "team" -> {
                return handleTeam(sender, args);
            }
            case "grant" -> {
                if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
                if (args.length < 3) return error(sender, "/facility grant <player> <team>");
                return handleGrant(sender, args[1], args[2], true);
            }
            case "revoke" -> {
                if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
                if (args.length < 3) return error(sender, "/facility revoke <player> <team>");
                return handleGrant(sender, args[1], args[2], false);
            }
            case "reload" -> {
                if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
                reloadConfig();
                teams.load();
                sender.sendMessage(Component.text("Facility config reloaded - teams: "
                    + teams.ids(), NamedTextColor.AQUA));
                return true;
            }
            default -> {
                return usage(sender);
            }
        }
    }

    /** /facility team ... - the join path AND the admin add/remove path. */
    private boolean handleTeam(CommandSender sender, String[] args) {
        if (args.length < 2) return error(sender, "/facility team <teamName|add|remove>");
        String sub = args[1].toLowerCase(Locale.ROOT);

        if (sub.equals("add")) {
            if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
            if (args.length < 5) return error(sender, "/facility team add <name> <public|private> <rank> [ICON] [prefix...]");
            boolean priv = args[3].equalsIgnoreCase("private");
            if (!priv && !args[3].equalsIgnoreCase("public")) return error(sender, "Type must be public or private.");
            Material icon = args.length >= 6 ? Material.matchMaterial(args[5]) : Material.PAPER;
            if (icon == null) icon = Material.PAPER;
            // Anything after the icon is the chat prefix (supports & colours + spaces).
            String prefix = args.length >= 7 ? String.join(" ", java.util.Arrays.copyOfRange(args, 6, args.length)) : "";
            teams.addOrUpdate(args[2], priv, args[4], icon, prefix);
            sender.sendMessage(Component.text("Team '" + args[2].toLowerCase(Locale.ROOT) + "' saved ("
                + (priv ? "private" : "public") + ", rank " + args[4] + ", icon " + icon.name()
                + (prefix.isBlank() ? "" : ", prefix " + prefix) + "). Edit permissions in config.yml.",
                NamedTextColor.AQUA));
            return true;
        }
        if (sub.equals("remove")) {
            if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
            if (args.length < 3) return error(sender, "/facility team remove <name>");
            if (!teams.remove(args[2])) return error(sender, "No team named '" + args[2] + "'.");
            sender.sendMessage(Component.text("Team '" + args[2].toLowerCase(Locale.ROOT) + "' removed.",
                NamedTextColor.AQUA));
            return true;
        }

        // Otherwise: a player joining a team.
        if (!(sender instanceof Player player)) return error(sender, "Players only.");
        if (!player.hasPermission("facility.use")) return error(sender, "No permission.");
        Team team = teams.get(sub);
        if (team == null) return error(sender, "No team named '" + args[1] + "'.");
        if (!teams.mayJoin(team, player)) {
            return error(sender, "This team is private - ask an admin for access.");
        }
        teams.applyRank(player, team);
        player.sendMessage(Component.text("You joined ", NamedTextColor.GREEN)
            .append(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(team.display()))
            .append(Component.text(".", NamedTextColor.GREEN)));
        return true;
    }

    private boolean handleGrant(CommandSender sender, String playerName, String teamName, boolean grant) {
        Team team = teams.get(teamName);
        if (team == null) return error(sender, "No team named '" + teamName + "'.");
        if (!team.privateTeam()) return error(sender, "Team '" + team.id() + "' is public - grants only apply to private teams.");
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(playerName);
        if (target == null) target = Bukkit.getOfflinePlayer(playerName);   // may create a UUID lookup
        if (grant) {
            teams.grant(team.id(), target.getUniqueId());
            sender.sendMessage(Component.text("Granted " + playerName + " access to '" + team.id() + "'.",
                NamedTextColor.AQUA));
        } else {
            boolean had = teams.revoke(team.id(), target.getUniqueId());
            sender.sendMessage(Component.text(had
                ? "Revoked " + playerName + "'s access to '" + team.id() + "'."
                : playerName + " had no access to '" + team.id() + "'.", NamedTextColor.AQUA));
        }
        return true;
    }

    private void openTeams(Player player) {
        fallback.openTeams(player);
    }

    // --- tab completion -----------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return switch (args.length) {
            case 1 -> filter(Stream.of("continue", "teams", "team", "grant", "revoke", "reload"), args[0]);
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "team" -> {
                    List<String> opts = new ArrayList<>(teams.ids());
                    opts.add("add");
                    opts.add("remove");
                    yield filter(opts.stream(), args[1]);
                }
                case "grant", "revoke" -> filter(online(), args[1]);
                default -> List.of();
            };
            case 3 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "team" -> args[1].equalsIgnoreCase("remove")
                    ? filter(teams.ids().stream(), args[2]) : List.of();
                case "grant", "revoke" -> filter(privateTeamIds(), args[2]);
                default -> List.of();
            };
            case 4 -> args[0].equalsIgnoreCase("team") && args[1].equalsIgnoreCase("add")
                ? filter(Stream.of("public", "private"), args[3]) : List.of();
            case 6 -> args[0].equalsIgnoreCase("team") && args[1].equalsIgnoreCase("add")
                ? filter(Stream.of("IRON_CHESTPLATE", "NETHERITE_HELMET", "PAPER", "ORANGE_WOOL"), args[5]) : List.of();
            default -> List.of();
        };
    }

    private Stream<String> online() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName);
    }

    private Stream<String> privateTeamIds() {
        return teams.all().stream().filter(Team::privateTeam).map(Team::id);
    }

    private List<String> filter(Stream<String> options, String prefix) {
        return options.filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT)))
            .sorted().toList();
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/facility continue | teams | team <name> | team add <name> <public|private> <rank> [ICON] | "
            + "team remove <name> | grant <player> <team> | revoke <player> <team> | reload",
            NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
