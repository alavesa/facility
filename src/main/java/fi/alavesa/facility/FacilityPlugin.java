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
    private DialogMenu dialogMenu;
    private CombatLogListener combat;
    private MenuStore menuStore;
    private MenuEditor menuEditor;
    private BlackoutManager blackout;
    private AreaManager areas;

    private NamespacedKey teamKey;

    public PlayerStore store() { return store; }
    public AreaManager areas() { return areas; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        teamKey = new NamespacedKey(this, "team_id");

        store = new PlayerStore(this);
        teams = new TeamManager(this);
        teams.load();

        menuStore = new MenuStore(this);
        dialogMenu = new DialogMenu(this, teams, menuStore);
        combat = new CombatLogListener(this, store);
        lobby = new LobbyManager(this, store, dialogMenu, teams, combat);
        menuEditor = new MenuEditor(this, menuStore);
        blackout = new BlackoutManager(this);
        areas = new AreaManager(this);

        getServer().getPluginManager().registerEvents(lobby, this);
        getServer().getPluginManager().registerEvents(combat, this);
        getServer().getPluginManager().registerEvents(menuEditor, this);
        getServer().getPluginManager().registerEvents(new StatsListener(store), this);
        // The countdown bar ticks every 5 ticks (4 Hz) for smooth drain.
        getServer().getScheduler().runTaskTimer(this, combat, 20L, 5L);
        // Area effects / last-area / tab readout, once a second.
        getServer().getScheduler().runTaskTimer(this, new AreaTask(this, areas, store), 40L, 20L);

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
        if (blackout != null && blackout.isActive()) blackout.end();   // restore lights
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
                // Can't PLAY until a valid team has been chosen - route them to
                // the selector instead of letting them into the world team-less.
                String teamId = store.getTeam(player.getUniqueId());
                if (teamId == null || teams.get(teamId) == null) {
                    player.sendMessage(Component.text("Choose a team before you deploy.",
                        NamedTextColor.RED));
                    openTeams(player);
                    return true;
                }
                lobby.continueInto(player);
                return true;
            }
            case "teams", "selector" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (!player.hasPermission("facility.use")) return error(sender, "No permission.");
                openTeams(player);
                return true;
            }
            case "back" -> {
                // The team selector's «Back button: reopen the main menu with NO
                // stand-still hold (the player is already in the menu).
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (!player.hasPermission("facility.use")) return error(sender, "No permission.");
                lobby.reopenMain(player);
                return true;
            }
            case "stats" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (!player.hasPermission("facility.use")) return error(sender, "No permission.");
                dialogMenu.openStats(player);
                return true;
            }
            case "area" -> {
                return handleArea(sender, args);
            }
            case "spawn" -> {
                // Spawn-picker button: deploy the player at their chosen spawn.
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                if (!player.hasPermission("facility.use")) return error(sender, "No permission.");
                String teamId = store.getTeam(player.getUniqueId());
                java.util.List<TeamManager.SpawnPoint> spawns =
                    teamId == null ? java.util.List.of() : teams.getSpawns(teamId);
                int idx;
                try { idx = Integer.parseInt(args[1]) - 1; }
                catch (Exception e) { return error(sender, "Pick a spawn from the menu."); }
                if (idx < 0 || idx >= spawns.size()) {
                    lobby.openMainMenu(player);   // stale choice - back to the menu
                    return true;
                }
                lobby.continueTo(player, spawns.get(idx).loc());
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
            case "menu" -> {
                return handleMenu(sender, args);
            }
            case "blackout" -> {
                if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
                String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
                int seconds = 0;
                if (args.length >= 3) {
                    try { seconds = Math.max(0, Integer.parseInt(args[2])); }
                    catch (NumberFormatException e) { return error(sender, "Seconds must be a number."); }
                }
                switch (sub) {
                    case "on" -> { blackout.start(seconds); }
                    case "off" -> { blackout.end(); }
                    default -> { blackout.toggle(seconds); }
                }
                sender.sendMessage(Component.text("Blackout " + (blackout.isActive() ? "ENGAGED" : "lifted")
                    + (blackout.isActive() && seconds > 0 ? " for " + seconds + "s" : "") + ".",
                    NamedTextColor.AQUA));
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
        if (sub.equals("setspawn")) {
            if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
            if (!(sender instanceof Player admin)) return error(sender, "Players only.");
            if (args.length < 3) return error(sender, "/facility team setspawn <name>");
            Team t = teams.get(args[2]);
            if (t == null) return error(sender, "No team named '" + args[2] + "'.");
            teams.setSpawn(t.id(), admin.getLocation());   // replaces all with one
            sender.sendMessage(Component.text("Spawn for '" + t.id() + "' set to your location "
                + "(replaced any others). Use 'addspawn' for multiple.", NamedTextColor.AQUA));
            return true;
        }
        if (sub.equals("addspawn")) {
            if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
            if (!(sender instanceof Player admin)) return error(sender, "Players only.");
            if (args.length < 3) return error(sender, "/facility team addspawn <name> [label...]");
            Team t = teams.get(args[2]);
            if (t == null) return error(sender, "No team named '" + args[2] + "'.");
            String label = args.length >= 4
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
                : "Spawn " + (teams.getSpawns(t.id()).size() + 1);
            teams.addSpawn(t.id(), label, admin.getLocation());
            sender.sendMessage(Component.text("Added spawn '" + label + "' to '" + t.id() + "' ("
                + teams.getSpawns(t.id()).size() + " total, mode: " + teams.getSpawnMode(t.id()) + ").",
                NamedTextColor.AQUA));
            return true;
        }
        if (sub.equals("delspawn")) {
            if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
            if (args.length < 4) return error(sender, "/facility team delspawn <name> <index>");
            Team t = teams.get(args[2]);
            if (t == null) return error(sender, "No team named '" + args[2] + "'.");
            int idx;
            try { idx = Integer.parseInt(args[3]); } catch (NumberFormatException e) {
                return error(sender, "Index must be a number (see /facility team spawns " + t.id() + ").");
            }
            if (!teams.removeSpawn(t.id(), idx)) return error(sender, "No spawn #" + idx + " on '" + t.id() + "'.");
            sender.sendMessage(Component.text("Removed spawn #" + idx + " from '" + t.id() + "'.",
                NamedTextColor.AQUA));
            return true;
        }
        if (sub.equals("spawns")) {
            if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
            if (args.length < 3) return error(sender, "/facility team spawns <name>");
            Team t = teams.get(args[2]);
            if (t == null) return error(sender, "No team named '" + args[2] + "'.");
            java.util.List<TeamManager.SpawnPoint> list = teams.getSpawns(t.id());
            sender.sendMessage(Component.text("'" + t.id() + "' spawns (mode: "
                + teams.getSpawnMode(t.id()) + "):", NamedTextColor.AQUA));
            if (list.isEmpty()) sender.sendMessage(Component.text("  (none)", NamedTextColor.GRAY));
            for (int i = 0; i < list.size(); i++) {
                TeamManager.SpawnPoint sp = list.get(i);
                sender.sendMessage(Component.text("  " + (i + 1) + ". " + sp.name() + " @ "
                    + sp.loc().getBlockX() + " " + sp.loc().getBlockY() + " " + sp.loc().getBlockZ(),
                    NamedTextColor.GRAY));
            }
            return true;
        }
        if (sub.equals("spawnmode")) {
            if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
            if (args.length < 4 || !(args[3].equalsIgnoreCase("random") || args[3].equalsIgnoreCase("choose")))
                return error(sender, "/facility team spawnmode <name> <random|choose>");
            Team t = teams.get(args[2]);
            if (t == null) return error(sender, "No team named '" + args[2] + "'.");
            teams.setSpawnMode(t.id(), args[3]);
            sender.sendMessage(Component.text("'" + t.id() + "' spawn mode: " + teams.getSpawnMode(t.id())
                + (teams.getSpawnMode(t.id()).equals("choose") ? " (players pick a spawn)"
                    : " (a random spawn each time)") + ".", NamedTextColor.AQUA));
            return true;
        }

        // Otherwise: a player joining a team.
        if (!(sender instanceof Player player)) return error(sender, "Players only.");
        if (!player.hasPermission("facility.use")) return error(sender, "No permission.");
        Team team = teams.get(sub);
        if (team == null) return error(sender, "No team named '" + args[1] + "'.");
        if (!teams.mayJoin(team, player)) {
            player.sendMessage(Component.text("This team is private - ask an admin for access.",
                NamedTextColor.RED));
            openTeams(player);   // the dialog closed on click; reopen it, don't strand them
            return true;
        }
        teams.applyRank(player, team);
        store.setTeam(player.getUniqueId(), team.id());   // Continue + respawn use its spawn
        player.sendMessage(Component.text("You joined ", NamedTextColor.GREEN)
            .append(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(team.display()))
            .append(Component.text(".", NamedTextColor.GREEN)));
        // Back to the main menu so they can press PLAY to leave spectator.
        lobby.openMainMenu(player);
        return true;
    }

    /**
     * /facility menu ... - the operator editor for the MAIN dialog contents.
     * All admin-only. Mutations persist through {@link MenuStore} and are
     * reflected the next time the dialog is opened (DialogMenu reads live).
     */
    private boolean handleMenu(CommandSender sender, String[] args) {
        if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
        if (args.length < 2) return menuUsage(sender);
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                List<MenuElement> els = menuStore.elements();
                sender.sendMessage(Component.text("Menu elements (" + els.size() + "):",
                    NamedTextColor.AQUA));
                for (int i = 0; i < els.size(); i++) {
                    MenuElement el = els.get(i);
                    String desc = el.type() == MenuElement.Type.BUTTON
                        ? "button  \"" + el.label() + "\" -> /" + el.action()
                        : "text    \"" + el.label() + "\"";
                    sender.sendMessage(Component.text("  #" + (i + 1) + "  " + desc,
                        NamedTextColor.GRAY));
                }
                return true;
            }
            case "edit" -> {
                if (!(sender instanceof Player player)) return error(sender, "Players only.");
                menuEditor.open(player);
                return true;
            }
            case "add" -> {
                if (args.length < 3) return error(sender, "/facility menu add <button <action> <label...>|text <label...>>");
                String kind = args[2].toLowerCase(Locale.ROOT);
                if (kind.equals("button")) {
                    if (args.length < 5) return error(sender, "/facility menu add button <action> <label...>");
                    String action = args[3];
                    String label = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                    menuStore.add(MenuElement.button(label, action));
                    sender.sendMessage(Component.text("Added button \"" + label + "\" -> /" + action,
                        NamedTextColor.AQUA));
                    return true;
                }
                if (kind.equals("text")) {
                    if (args.length < 4) return error(sender, "/facility menu add text <label...>");
                    String label = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                    menuStore.add(MenuElement.text(label));
                    sender.sendMessage(Component.text("Added text line \"" + label + "\"",
                        NamedTextColor.AQUA));
                    return true;
                }
                return error(sender, "Add type must be 'button' or 'text'.");
            }
            case "remove" -> {
                Integer idx = index(sender, args, 2);
                if (idx == null) return true;
                MenuElement removed = menuStore.remove(idx);
                if (removed == null) return error(sender, "No element #" + (idx + 1) + ".");
                sender.sendMessage(Component.text("Removed element #" + (idx + 1) + ".",
                    NamedTextColor.AQUA));
                return true;
            }
            case "move" -> {
                if (args.length < 4) return error(sender, "/facility menu move <index> up|down");
                Integer idx = index(sender, args, 2);
                if (idx == null) return true;
                int delta = args[3].equalsIgnoreCase("up") ? -1
                    : args[3].equalsIgnoreCase("down") ? +1 : 0;
                if (delta == 0) return error(sender, "Direction must be up or down.");
                if (!menuStore.move(idx, delta)) return error(sender, "Can't move #" + (idx + 1) + " that way.");
                sender.sendMessage(Component.text("Moved element #" + (idx + 1) + " "
                    + args[3].toLowerCase(Locale.ROOT) + ".", NamedTextColor.AQUA));
                return true;
            }
            case "setlabel" -> {
                if (args.length < 4) return error(sender, "/facility menu setlabel <index> <label...>");
                Integer idx = index(sender, args, 2);
                if (idx == null) return true;
                String label = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                if (!menuStore.setLabel(idx, label)) return error(sender, "No element #" + (idx + 1) + ".");
                sender.sendMessage(Component.text("Element #" + (idx + 1) + " label set.",
                    NamedTextColor.AQUA));
                return true;
            }
            case "setaction" -> {
                if (args.length < 4) return error(sender, "/facility menu setaction <index> <command...>");
                Integer idx = index(sender, args, 2);
                if (idx == null) return true;
                String action = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                if (!menuStore.setAction(idx, action)) {
                    return error(sender, "No button #" + (idx + 1) + " (only buttons have actions).");
                }
                sender.sendMessage(Component.text("Element #" + (idx + 1) + " action set to /" + action,
                    NamedTextColor.AQUA));
                return true;
            }
            default -> {
                return menuUsage(sender);
            }
        }
    }

    /** Parse a 1-based index arg into a 0-based index, messaging on failure.
     *  Returns null (and messages) if missing / not a number. */
    private Integer index(CommandSender sender, String[] args, int pos) {
        if (args.length <= pos) {
            error(sender, "Missing element number.");
            return null;
        }
        try {
            int one = Integer.parseInt(args[pos]);
            if (one < 1) { error(sender, "Element number starts at 1."); return null; }
            return one - 1;
        } catch (NumberFormatException e) {
            error(sender, "'" + args[pos] + "' is not a number.");
            return null;
        }
    }

    private boolean menuUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
            "/facility menu list | edit | add button <action> <label...> | add text <label...> | "
            + "remove <index> | move <index> up|down | setlabel <index> <label...> | "
            + "setaction <index> <command...>", NamedTextColor.AQUA));
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
        dialogMenu.openTeams(player);
    }

    private boolean handleArea(CommandSender sender, String[] args) {
        if (!sender.hasPermission("facility.admin")) return error(sender, "No permission.");
        if (!(sender instanceof Player player)) return error(sender, "Players only.");
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "pos1" -> {
                int[] c = areas.setPos1(player);
                return c == null ? error(sender, "Look at a block (within 100), then run the command.")
                    : ok(sender, "Corner 1 set to the block you're looking at: " + c[0] + ", " + c[1] + ", " + c[2] + ".");
            }
            case "pos2" -> {
                int[] c = areas.setPos2(player);
                return c == null ? error(sender, "Look at a block (within 100), then run the command.")
                    : ok(sender, "Corner 2 set to the block you're looking at: " + c[0] + ", " + c[1] + ", " + c[2] + ".");
            }
            case "create" -> {
                if (args.length < 3) return error(sender, "/facility area create <name>");
                String problem = areas.create(player, args[2]);
                if (problem != null) return error(sender, problem);
                return ok(sender, "Area '" + args[2] + "' created. Add effects with /facility area effect "
                    + args[2] + " <EFFECT:amp>, or /facility area scp008 " + args[2] + " on.");
            }
            case "remove" -> {
                if (args.length < 3) return error(sender, "/facility area remove <name>");
                return areas.remove(args[2]) ? ok(sender, "Removed area '" + args[2] + "'.")
                    : error(sender, "No area named '" + args[2] + "'.");
            }
            case "list" -> {
                var all = areas.all();
                if (all.isEmpty()) return ok(sender, "No areas defined yet.");
                for (var a : all) {
                    sender.sendMessage(Component.text("• " + a.name() + "  [" + a.world() + "]  "
                        + (a.scp008() ? "SCP-008 " : "") + a.effects(), NamedTextColor.GRAY));
                }
                return true;
            }
            case "effect" -> {
                if (args.length < 4) return error(sender, "/facility area effect <name> <EFFECT:amplifier>");
                String problem = areas.addEffect(args[2], args[3]);
                if (problem != null) return error(sender, problem);
                return ok(sender, "Added effect " + args[3] + " to '" + args[2] + "'.");
            }
            case "cleareffects" -> {
                if (args.length < 3) return error(sender, "/facility area cleareffects <name>");
                areas.clearEffects(args[2]);
                return ok(sender, "Cleared effects on '" + args[2] + "'.");
            }
            case "scp008" -> {
                if (args.length < 4) return error(sender, "/facility area scp008 <name> <on|off>");
                boolean on = args[3].equalsIgnoreCase("on");
                return areas.setScp008(args[2], on) ? ok(sender, "SCP-008 infection " + (on ? "on" : "off")
                    + " for '" + args[2] + "'.") : error(sender, "No area named '" + args[2] + "'.");
            }
            default -> {
                sender.sendMessage(Component.text(
                    "/facility area pos1|pos2 | create <name> | remove <name> | list | "
                    + "effect <name> <EFFECT:amp> | cleareffects <name> | scp008 <name> <on|off>",
                    NamedTextColor.AQUA));
                return true;
            }
        }
    }

    private boolean ok(CommandSender sender, String msg) {
        sender.sendMessage(Component.text(msg, NamedTextColor.GRAY));
        return true;
    }

    // --- tab completion -----------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("facility.admin");
        return switch (args.length) {
            case 1 -> {
                List<String> top = new ArrayList<>(List.of("continue", "teams", "team", "stats"));
                if (admin) top.addAll(List.of("grant", "revoke", "reload", "menu", "blackout", "area"));
                yield filter(top.stream(), args[0]);
            }
            case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "team" -> {
                    List<String> opts = new ArrayList<>(teams.ids());
                    opts.addAll(List.of("add", "remove", "setspawn", "addspawn", "delspawn",
                        "spawns", "spawnmode"));
                    yield filter(opts.stream(), args[1]);
                }
                case "grant", "revoke" -> filter(online(), args[1]);
                case "menu" -> admin ? filter(Stream.of("list", "edit", "add", "remove",
                    "move", "setlabel", "setaction"), args[1]) : List.of();
                case "blackout" -> admin ? filter(Stream.of("on", "off", "toggle"), args[1]) : List.of();
                case "area" -> admin ? filter(Stream.of("pos1", "pos2", "create", "remove", "list",
                    "effect", "cleareffects", "scp008"), args[1]) : List.of();
                default -> List.of();
            };
            case 3 -> switch (args[0].toLowerCase(Locale.ROOT)) {
                case "team" -> Stream.of("remove", "setspawn", "addspawn", "delspawn", "spawns", "spawnmode")
                    .anyMatch(args[1]::equalsIgnoreCase)
                    ? filter(teams.ids().stream(), args[2]) : List.of();
                case "grant", "revoke" -> filter(privateTeamIds(), args[2]);
                case "menu" -> menuArgComplete(args);
                default -> List.of();
            };
            case 4 -> {
                if (args[0].equalsIgnoreCase("team") && args[1].equalsIgnoreCase("add"))
                    yield filter(Stream.of("public", "private"), args[3]);
                if (args[0].equalsIgnoreCase("team") && args[1].equalsIgnoreCase("spawnmode"))
                    yield filter(Stream.of("random", "choose"), args[3]);
                if (args[0].equalsIgnoreCase("menu") && args[1].equalsIgnoreCase("move"))
                    yield filter(Stream.of("up", "down"), args[3]);
                yield List.of();
            }
            case 6 -> args[0].equalsIgnoreCase("team") && args[1].equalsIgnoreCase("add")
                ? filter(Stream.of("IRON_CHESTPLATE", "NETHERITE_HELMET", "PAPER", "ORANGE_WOOL"), args[5]) : List.of();
            default -> List.of();
        };
    }

    /** Third-arg completion for /facility menu <sub> ... */
    private List<String> menuArgComplete(String[] args) {
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("add")) return filter(Stream.of("button", "text"), args[2]);
        if (sub.equals("remove") || sub.equals("move")
            || sub.equals("setlabel") || sub.equals("setaction")) {
            List<String> nums = new ArrayList<>();
            for (int i = 1; i <= menuStore.size(); i++) nums.add(String.valueOf(i));
            return filter(nums.stream(), args[2]);
        }
        return List.of();
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
            + "team remove <name> | grant <player> <team> | revoke <player> <team> | reload | menu ...",
            NamedTextColor.AQUA));
        return true;
    }

    private boolean error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
        return true;
    }
}
