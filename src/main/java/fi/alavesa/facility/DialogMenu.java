package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The lobby menu as a native Minecraft DIALOG (1.21.6+ /dialog feature) - the
 * same system DonutSMP uses. No chest, no book, no resource pack: these are
 * real full-screen GUIs with real buttons, rendered by the client.
 *
 * The plugin builds each dialog INLINE as SNBT and shows it with
 * {@code /dialog show <player> {...}}. Because it's rebuilt from config every
 * time, teams added with {@code /facility team add} appear in the selector
 * automatically. {@code can_close_with_escape:false} makes the main menu an
 * un-escapable lobby lock - the only way out is the PLAY button.
 *
 * Text is authored as Adventure Components and serialized to JSON, which is
 * valid SNBT for the dialog's text-component fields.
 */
public final class DialogMenu {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final TeamManager teams;

    public DialogMenu(TeamManager teams) {
        this.teams = teams;
    }

    public void openMain(Player player) {
        show(player, mainDialog());
    }

    public void openTeams(Player player) {
        show(player, teamsDialog(player));
    }

    private void show(Player player, String snbt) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
            "dialog show " + player.getName() + " " + snbt);
    }

    // --- dialog builders ----------------------------------------------------

    private String mainDialog() {
        String actions =
            button(Component.text("▶ PLAY", NamedTextColor.GREEN, TextDecoration.BOLD), "facility continue")
            + "," + button(Component.text("SELECT TEAM", NamedTextColor.AQUA), "facility teams");
        return dialog("SITE-19 // MAIN MENU", "Welcome to Site-19. Choose an option.", actions);
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
        actions.append(button(Component.text("« Back", NamedTextColor.GRAY), "menu"));
        return dialog("SELECT TEAM", "Choose your team.", actions.toString());
    }

    /** A multi_action dialog: title, one body line, a column of action buttons,
     *  un-escapable so it doubles as the lobby lock. */
    private String dialog(String title, String body, String actionsCsv) {
        return "{type:\"minecraft:multi_action\""
            + ",title:" + json(Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD))
            + ",can_close_with_escape:false"
            + ",body:[{type:\"minecraft:plain_message\",contents:"
                + json(Component.text(body, NamedTextColor.GRAY)) + "}]"
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
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
