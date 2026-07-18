package fi.alavesa.facility;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/**
 * The lobby menu as a WRITTEN BOOK, not a chest. Opened with player.openBook(),
 * so there are no item slots and no chest chrome: the buttons are real clickable
 * text components ({@code run_command}). Each fixed button is a single bitmap
 * FONT GLYPH (facility:book) - a full-width gray bar with its label baked in -
 * and on hover the component's tooltip shows a WHITE-OUTLINED variant of the same
 * bar. A transparent tooltip texture (shipped in the pack) makes that read as an
 * overlay lighting up the button, like the DonutSMP /settings reference.
 *
 * Positions (the leading nudge and the hover rewind) are deliberately named
 * constants - blind pixel work, tuned from in-game screenshots.
 */
public final class BookMenu {

    private static final Key FONT = Key.key("facility", "book");

    // Glyphs (see tools/gen_book.py). *_H are the white-outlined hover variants.
    private static final String PLAY = "\uE010";
    private static final String PLAY_H = "\uE011";
    private static final String TEAM = "\uE012";
    private static final String TEAM_H = "\uE013";
    private static final String NUDGE = "\uF810";   // +2px spacing tweak
    private static final String REWIND = "\uF811";  // -112px, pulls the hover glyph over the button

    private final TeamManager teams;

    public BookMenu(TeamManager teams) {
        this.teams = teams;
    }

    // --- main menu ----------------------------------------------------------

    public void openMain(Player player) {
        Component page = Component.text()
            .append(Component.newline())
            .append(Component.text("SITE-19", NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
            .append(Component.newline()).append(Component.newline())
            .append(barButton(PLAY, PLAY_H, "/facility continue"))
            .append(Component.newline()).append(Component.newline())
            .append(barButton(TEAM, TEAM_H, "/facility teams"))
            .build();
        openBook(player, page);
    }

    // --- team selector ------------------------------------------------------

    public void openTeams(Player player) {
        net.kyori.adventure.text.TextComponent.Builder page = Component.text()
            .append(Component.newline())
            .append(Component.text("SELECT TEAM", NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
            .append(Component.newline());

        for (Team team : teams.all()) {
            page.append(Component.newline());
            boolean allowed = teams.mayJoin(team, player);
            Component name = legacy(team.display());
            if (allowed) {
                page.append(Component.text("» ", NamedTextColor.DARK_GRAY)
                    .append(name)
                    .clickEvent(ClickEvent.runCommand("/facility team " + team.id()))
                    .hoverEvent(HoverEvent.showText(Component.text()
                        .append(Component.text("Deploy as ", NamedTextColor.GRAY)).append(name)
                        .append(Component.newline())
                        .append(Component.text("Rank: " + team.rank(), NamedTextColor.DARK_GRAY))
                        .build())));
            } else {
                page.append(Component.text("✖ ", NamedTextColor.RED)
                    .append(name.colorIfAbsent(NamedTextColor.GRAY))
                    .hoverEvent(HoverEvent.showText(
                        Component.text("Private - ask an admin for access.", NamedTextColor.RED))));
            }
        }
        page.append(Component.newline()).append(Component.newline())
            .append(Component.text("« Back", NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand("/menu")));
        openBook(player, page.build());
    }

    // --- helpers ------------------------------------------------------------

    /** A clickable bar-glyph button; hovering shows the white-outlined variant,
     *  pulled left by REWIND so it overlays the button (transparent tooltip). */
    private Component barButton(String glyph, String hoverGlyph, String command) {
        return Component.text(NUDGE + glyph).font(FONT)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(
                Component.text(REWIND + hoverGlyph).font(FONT)));
    }

    private void openBook(Player player, Component page) {
        Book book = Book.book(Component.text("SITE-19"), Component.text("Facility"), page);
        player.openBook(book);
    }

    private Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
            .decoration(TextDecoration.ITALIC, false);
    }
}
