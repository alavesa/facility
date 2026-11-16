package fi.alavesa.facility;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The lobby menu as a native Minecraft DIALOG (1.21.6+ /dialog feature) - the
 * same system DonutSMP uses. No chest, no book: these are real full-screen
 * GUIs with real buttons, rendered by the client.
 *
 * The plugin builds each dialog INLINE as SNBT and shows it with
 * {@code /dialog show <player> {...}}. The MAIN dialog is now built from the
 * operator-editable {@link MenuStore} element list (buttons + text lines), so
 * admins can reshape it at runtime via commands or the chest editor. The TEAM
 * selector is rebuilt live from config teams, STATS from the player's record
 * and CREDITS from {@code menu.credits}; every title, prompt and stock button
 * label comes from {@code menu.text} (see {@link #text}). {@code
 * can_close_with_escape:false} makes the main menu an un-escapable lobby lock.
 *
 * The main dialog also carries a resource-pack BACKGROUND: a bitmap glyph in
 * the {@code facility:dialog} font (see tools/gen_dialog_bg.py) painted as the
 * first body line, pulled into place by a negative-space rewind. The glyph
 * codepoints below MUST match font/dialog.json exactly.
 *
 * Text is authored as Adventure Components and serialized to JSON, which is
 * valid SNBT for the dialog's text-component fields.
 */
public final class DialogMenu {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    /** The background font + glyph codepoints - keep in sync with
     *  resource-pack/assets/facility/font/dialog.json (gen_dialog_bg.py).
     *  U+E000 rewind advance, U+E001 fine nudge, U+E010 the panel bitmap. */
    private static final Key BG_FONT = Key.key("facility", "dialog");
    private static final char BG_REWIND = '\ue000';
    private static final char BG_NUDGE  = '\ue001';   // repeat to fine-tune X
    private static final char BG_PANEL  = '\ue010';
    /** How many 1px nudge glyphs to prepend after the rewind (blind pixel work -
     *  needs in-game screenshot tuning). 0 = panel starts at the rewind point. */
    private static final int BG_NUDGE_COUNT = 0;

    /** Text widths in the sub-dialogs, GUI pixels (the client allows 1-1024; 200 is its default). */
    private static final int CARD_WIDTH = 260;
    private static final int CREDITS_WIDTH = 300;

    private final FacilityPlugin plugin;
    private final TeamManager teams;
    private final MenuStore menu;

    public DialogMenu(FacilityPlugin plugin, TeamManager teams, MenuStore menu) {
        this.plugin = plugin;
        this.teams = teams;
        this.menu = menu;
    }

    public void openMain(Player player) {
        show(player, mainDialog());
        showBackground(player);
    }

    /** The custom main-menu background, painted as a custom-textured SUBTITLE glyph
     *  BEHIND the dialog - instead of an extra button/element inside the dialog, so
     *  the full background shows. Long stay time (the player sits in the menu); it's
     *  cleared on PLAY (LobbyManager.continueTo calls player.clearTitle()). */
    public void showBackground(Player player) {
        if (!plugin.getConfig().getBoolean("menu.background.enabled", true)) return;
        Component glyph = Component.text(String.valueOf(BG_PANEL)).font(BG_FONT)
            .color(NamedTextColor.WHITE);
        player.showTitle(net.kyori.adventure.title.Title.title(Component.empty(), glyph,
            net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO,
                java.time.Duration.ofMinutes(5), java.time.Duration.ZERO)));
    }

    public void openTeams(Player player) {
        show(player, teamsDialog(player));
    }

    public void openStats(Player player) {
        show(player, statsDialog(player));
    }

    public void openCredits(Player player) {
        show(player, creditsDialog());
    }

    /** Let the player pick which of their team's spawns to deploy at. Each button
     *  runs {@code facility spawn <index>}; a «Back returns to the main menu. */
    public void openSpawnChoice(Player player, java.util.List<TeamManager.SpawnPoint> spawns) {
        StringBuilder actions = new StringBuilder();
        for (int i = 0; i < spawns.size(); i++) {
            actions.append(button(Component.text(spawns.get(i).name(), NamedTextColor.AQUA),
                "facility spawn " + (i + 1))).append(",");
        }
        actions.append(back());
        show(player, dialog(text("spawn-title", "&6&lSELECT SPAWN"),
            message(text("spawn-prompt", "&7Choose your spawn point.")), actions.toString()));
    }

    private void show(Player player, String snbt) {
        // ANY open dialog (main, teams, stats, spawn) means the player is in the menu:
        // publish facility.menu=1 so Labra's credits HUD yields the title layer to it,
        // instead of the two overlapping/alternating. Cleared when the player deploys.
        setMenuFlag(player, true);
        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "dialog show " + player.getName() + " " + snbt);
        if (!ok) {
            Bukkit.getLogger().warning("[Facility] 'dialog show' failed for " + player.getName()
                + " - does this server support the /dialog command (MC 1.21.6+)? The menu can't open."
                + " If /dialog is missing or the schema differs on your build, tell the developer.");
        }
    }

    /** Publish/clear facility.menu on the main scoreboard so Labra's credits HUD knows to
     *  stop drawing its title while the player has any menu dialog open. */
    static void setMenuFlag(Player player, boolean on) {
        try {
            var board = Bukkit.getScoreboardManager().getMainScoreboard();
            var o = board.getObjective("facility.menu");
            if (o == null) o = board.registerNewObjective("facility.menu",
                org.bukkit.scoreboard.Criteria.DUMMY, Component.text("facility.menu"));
            o.getScore(player.getName()).setScore(on ? 1 : 0);
        } catch (Exception ignored) { }
    }

    // --- dialog builders ----------------------------------------------------

    /** The MAIN dialog, built from the operator-editable element list: TEXT
     *  elements become body lines, BUTTON elements become action buttons. A
     *  malformed element can never appear here (MenuStore skips them). */
    private String mainDialog() {
        java.util.List<String> body = new java.util.ArrayList<>();
        // The custom background is no longer an in-dialog element (a button/body line
        // shrank/clipped it); it's now a full custom-textured SUBTITLE behind the
        // dialog - see showBackground(), sent from openMain().
        StringBuilder actions = new StringBuilder();
        for (MenuElement el : menu.elements()) {
            if (el.type() == MenuElement.Type.TEXT) {
                body.add("{type:\"minecraft:plain_message\",contents:" + json(legacy(el.label())) + "}");
            } else {
                if (actions.length() > 0) actions.append(",");
                actions.append(button(legacy(el.label()), el.action()));
            }
        }
        // The stats and credits screens are offered regardless of the operator's button list
        // (menu.stats.enabled / menu.credits.enabled turn them off).
        if (plugin.getConfig().getBoolean("menu.stats.enabled", true)) {
            if (actions.length() > 0) actions.append(",");
            actions.append(button(text("stats-button", "&eStats"), "facility stats"));
        }
        if (plugin.getConfig().getBoolean("menu.credits.enabled", true)) {
            if (actions.length() > 0) actions.append(",");
            actions.append(button(text("credits-button", "&7Credits"), "facility credits"));
        }
        return dialog(text("main-title", "&6&lSITE-19 // MAIN MENU"), String.join(",", body), actions.toString());
    }

    /** The team selector: one card per team in the body (icon, name, description,
     *  perks), then a join button per team. The body scrolls when the cards
     *  outgrow the screen; «Back is pinned in the footer. */
    private String teamsDialog(Player player) {
        java.util.List<String> body = new java.util.ArrayList<>();
        body.add(message(text("teams-prompt", "&7Choose your team.")));
        StringBuilder actions = new StringBuilder();
        for (Team team : teams.all()) {
            boolean locked = !teams.mayJoin(team, player);
            Component label = legacy(team.display());
            if (locked) label = label.append(text("team-locked", "&8  ✖ locked"));
            body.add(teamCard(team, label));
            // Either way the command runs: join grants + returns to menu; a denied
            // (private) join messages the player and reopens this selector.
            if (actions.length() > 0) actions.append(",");
            actions.append(button(label, "facility team " + team.id(),
                team.description().isBlank() ? null : legacy(team.description()), 150));
        }
        return dialog(text("teams-title", "&6&lSELECT TEAM"), String.join(",", body), actions.toString(), 2,
            button(text("back-button", "&7« Back"), "facility back"));
    }

    /** A team's card: its icon item with the name, description and perks beside it. */
    private String teamCard(Team team, Component name) {
        Component card = name.decorate(TextDecoration.BOLD);
        if (!team.description().isBlank()) {
            card = card.append(Component.newline())
                .append(legacy(team.description()).colorIfAbsent(NamedTextColor.GRAY)
                    .decoration(TextDecoration.BOLD, false));
        }
        for (String perk : team.perks()) {
            if (perk == null || perk.isBlank()) continue;
            card = card.append(Component.newline())
                .append(Component.text(" ▸ ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
                .append(legacy(perk).colorIfAbsent(NamedTextColor.DARK_AQUA).decoration(TextDecoration.BOLD, false));
        }
        if (!team.iconOr(org.bukkit.Material.PAPER).isItem()) return message(card);
        return "{type:\"minecraft:item\",item:{id:" + quote(team.iconOr(org.bukkit.Material.PAPER).getKey().toString())
            + "},description:{contents:" + json(card) + ",width:" + CARD_WIDTH + "}"
            + ",show_decorations:false,show_tooltip:false}";
    }

    /** The player's record: combat, deployments, time on site, last team and
     *  area, then whatever scoreboard objectives {@code menu.stats.extra} names
     *  (how the sibling plugins publish their numbers - research points, credits...). */
    private String statsDialog(Player player) {
        java.util.UUID id = player.getUniqueId();
        PlayerStore st = plugin.store();
        String teamId = st.getTeam(id);
        Team team = teamId == null ? null : teams.get(teamId);
        String lastArea = st.lastArea(id);
        java.util.List<String> body = new java.util.ArrayList<>();
        body.add(statLine("Kills", Component.text(st.kills(id), NamedTextColor.GREEN)));
        body.add(statLine("Deaths", Component.text(st.deaths(id), NamedTextColor.RED)));
        body.add(statLine("K/D", Component.text(String.valueOf(st.kd(id)), NamedTextColor.AQUA)));
        body.add(statLine("Deployments", Component.text(st.deploys(id), NamedTextColor.AQUA)));
        int minutes = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 1200;   // the stat counts ticks
        body.add(statLine("Time on site", Component.text((minutes / 60) + " h " + (minutes % 60) + " min",
            NamedTextColor.AQUA)));
        body.add(statLine("Last played as", team != null ? legacy(team.display())
            : Component.text("—", NamedTextColor.DARK_GRAY)));
        body.add(statLine("Last area", Component.text(lastArea == null ? "—" : lastArea, NamedTextColor.AQUA)));
        var board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (java.util.Map<?, ?> extra : plugin.getConfig().getMapList("menu.stats.extra")) {
            Object label = extra.get("label");
            Object objective = extra.get("objective");
            if (label == null || objective == null) continue;
            var o = board.getObjective(objective.toString());
            var score = o == null ? null : o.getScore(player.getName());
            body.add(statLine(label.toString(), score != null && score.isScoreSet()
                ? Component.text(score.getScore(), NamedTextColor.AQUA)
                : Component.text("—", NamedTextColor.DARK_GRAY)));
        }
        return dialog(text("stats-title", "&6&lYOUR RECORD"), String.join(",", body), "", 1, back());
    }

    /** Server lore, staff and licence notes: {@code menu.credits.lines}, one
     *  body line each (a blank entry is a gap). Scrolls; «Back stays in the footer. */
    private String creditsDialog() {
        java.util.List<String> body = new java.util.ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String line : plugin.getConfig().getStringList("menu.credits.lines")) {
            // consecutive lines share one text block so they sit tight; a blank line starts the next block
            if (line.isBlank()) {
                if (paragraph.length() > 0) body.add(message(legacy(paragraph.toString()), CREDITS_WIDTH));
                paragraph.setLength(0);
            } else {
                if (paragraph.length() > 0) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        if (paragraph.length() > 0) body.add(message(legacy(paragraph.toString()), CREDITS_WIDTH));
        return dialog(text("credits-title", "&6&lCREDITS"), String.join(",", body), "", 1, back());
    }

    private String statLine(String label, Component value) {
        return message(legacy(label + ": ").colorIfAbsent(NamedTextColor.GRAY).append(value));
    }

    /** One body text block. */
    private String message(Component c) {
        return "{type:\"minecraft:plain_message\",contents:" + json(c) + "}";
    }

    private String message(Component c, int width) {
        return "{type:\"minecraft:plain_message\",contents:" + json(c) + ",width:" + width + "}";
    }

    /** A title, prompt or stock button label from {@code menu.text.<key>} (&-coloured). */
    private Component text(String key, String def) {
        return legacy(plugin.getConfig().getString("menu.text." + key, def));
    }

    private String back() {
        return button(text("back-button", "&7« Back"), "facility back");
    }

    /** The background as a body element: the panel glyph, preceded by the
     *  negative-space rewind (and optional fine nudges) in the facility:dialog
     *  font. The offsets are blind pixel work; tune with an in-game screenshot. */
    private String bgBodyElement() {
        // REWIND + PANEL + REWIND: the two half-width rewinds cancel the panel's
        // own advance (so it doesn't widen the dialog) and leave it centered on
        // screen, backing the button column. BG_NUDGE fine-tunes X (repeat it).
        StringBuilder glyphs = new StringBuilder();
        glyphs.append(BG_REWIND);
        for (int i = 0; i < BG_NUDGE_COUNT; i++) glyphs.append(BG_NUDGE);
        glyphs.append(BG_PANEL);
        glyphs.append(BG_REWIND);
        Component bg = Component.text(glyphs.toString())
            .font(BG_FONT).color(NamedTextColor.WHITE);
        return "{type:\"minecraft:plain_message\",contents:" + json(bg) + "}";
    }

    /** A multi_action dialog: title, a body (raw SNBT element CSV), a column of
     *  action buttons, un-escapable so it doubles as the lobby lock. Callers
     *  pass the body already serialized (so it can carry the bg glyph). */
    /** Warn a player before they switch teams, since it wipes their current playthrough. Yes runs
     *  the confirmed team change; No returns to the team selector. */
    public void openTeamChangeConfirm(Player player, Team newTeam) {
        String actions =
            button(Component.text("Yes — change teams (wipes my playthrough & items)", NamedTextColor.RED),
                "facility teamconfirm " + newTeam.id())
            + "," + button(Component.text("« No, keep my current team", NamedTextColor.GRAY), "facility teams");
        Component warn = Component.text("Switching to ", NamedTextColor.GRAY)
            .append(legacy(newTeam.display()))
            .append(Component.text(" will WIPE your current playthrough — your saved position and ALL your items. "
                + "Are you sure?", NamedTextColor.RED));
        show(player, dialog(text("team-change-title", "&6&lCHANGE TEAM?"), message(warn), actions));
    }

    private String dialog(Component title, String bodyCsv, String actionsCsv) {
        return dialog(title, bodyCsv, actionsCsv, 1, null);
    }

    /** {@code exit} (a button, or null) sits in the dialog's footer, below the
     *  scrolling part. With no action buttons at all the dialog is a
     *  {@code notice}: the body and that one footer button. */
    private String dialog(Component title, String bodyCsv, String actionsCsv, int columns, String exit) {
        String head = ",title:" + json(title) + ",can_close_with_escape:false" + ",body:[" + bodyCsv + "]";
        if (actionsCsv.isEmpty() && exit != null) {
            return "{type:\"minecraft:notice\"" + head + ",action:" + exit + "}";
        }
        return "{type:\"minecraft:multi_action\"" + head
            + ",columns:" + columns
            + ",actions:[" + actionsCsv + "]"
            + (exit == null ? "" : ",exit_action:" + exit) + "}";
    }

    /** One full-width action button that runs a player command on click. */
    private String button(Component label, String command) {
        return button(label, command, null, 200);
    }

    private String button(Component label, String command, Component tooltip, int width) {
        return "{label:" + json(label) + ",width:" + width
            + (tooltip == null ? "" : ",tooltip:" + json(tooltip))
            + ",action:{type:\"minecraft:run_command\",command:" + quote(command) + "}}";
    }

    // --- SNBT helpers -------------------------------------------------------

    /** A Component as JSON, which is also valid SNBT for a text-component field. */
    private String json(Component c) {
        return GSON.serialize(c.decoration(TextDecoration.ITALIC, false));
    }

    private Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s == null ? "" : s);
    }

    private String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
