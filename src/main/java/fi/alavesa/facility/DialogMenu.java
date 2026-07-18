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
 * selector is still rebuilt live from config teams. {@code
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
    }

    public void openTeams(Player player) {
        show(player, teamsDialog(player));
    }

    private void show(Player player, String snbt) {
        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "dialog show " + player.getName() + " " + snbt);
        if (!ok) {
            Bukkit.getLogger().warning("[Facility] 'dialog show' failed for " + player.getName()
                + " - does this server support the /dialog command (MC 1.21.6+)? The menu can't open."
                + " If /dialog is missing or the schema differs on your build, tell the developer.");
        }
    }

    // --- dialog builders ----------------------------------------------------

    /** The MAIN dialog, built from the operator-editable element list: TEXT
     *  elements become body lines, BUTTON elements become action buttons. A
     *  malformed element can never appear here (MenuStore skips them). */
    private String mainDialog() {
        java.util.List<String> body = new java.util.ArrayList<>();
        // The custom background is opt-out: if the menu ever fails to render,
        // flip menu.background.enabled to false to rule the glyph in or out.
        if (plugin.getConfig().getBoolean("menu.background.enabled", false)) {
            body.add(bgBodyElement());
        }
        StringBuilder actions = new StringBuilder();
        for (MenuElement el : menu.elements()) {
            if (el.type() == MenuElement.Type.TEXT) {
                body.add("{type:\"minecraft:plain_message\",contents:" + json(legacy(el.label())) + "}");
            } else {
                if (actions.length() > 0) actions.append(",");
                actions.append(button(legacy(el.label()), el.action()));
            }
        }
        return dialog("SITE-19 // MAIN MENU", String.join(",", body), actions.toString());
    }

    private String teamsDialog(Player player) {
        StringBuilder actions = new StringBuilder();
        for (Team team : teams.all()) {
            Component label = legacy(team.display());
            if (!teams.mayJoin(team, player)) {
                label = label.append(Component.text("  ✖ locked", NamedTextColor.DARK_GRAY));
            }
            // Either way the command runs: join grants + returns to menu; a denied
            // (private) join messages the player and reopens this selector.
            actions.append(button(label, "facility team " + team.id())).append(",");
        }
        actions.append(button(Component.text("« Back", NamedTextColor.GRAY), "facility back"));
        String body = "{type:\"minecraft:plain_message\",contents:"
            + json(Component.text("Choose your team.", NamedTextColor.GRAY)) + "}";
        return dialog("SELECT TEAM", body, actions.toString());
    }

    /** The background as a body element: the panel glyph, preceded by the
     *  negative-space rewind (and optional fine nudges) in the facility:dialog
     *  font. The offsets are blind pixel work; tune with an in-game screenshot. */
    private String bgBodyElement() {
        StringBuilder glyphs = new StringBuilder();
        glyphs.append(BG_REWIND);
        for (int i = 0; i < BG_NUDGE_COUNT; i++) glyphs.append(BG_NUDGE);
        glyphs.append(BG_PANEL);
        Component bg = Component.text(glyphs.toString())
            .font(BG_FONT).color(NamedTextColor.WHITE);
        return "{type:\"minecraft:plain_message\",contents:" + json(bg) + "}";
    }

    /** A multi_action dialog: title, a body (raw SNBT element CSV), a column of
     *  action buttons, un-escapable so it doubles as the lobby lock. Callers
     *  pass the body already serialized (so it can carry the bg glyph). */
    private String dialog(String title, String bodyCsv, String actionsCsv) {
        return "{type:\"minecraft:multi_action\""
            + ",title:" + json(Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD))
            + ",can_close_with_escape:false"
            + ",body:[" + bodyCsv + "]"
            + ",columns:1"
            + ",actions:[" + actionsCsv + "]}";
    }

    /** One full-width action button that runs a player command on click. */
    private String button(Component label, String command) {
        return "{label:" + json(label) + ",width:200"
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
