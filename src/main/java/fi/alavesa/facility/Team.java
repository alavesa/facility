package fi.alavesa.facility;

import org.bukkit.Material;

import java.util.List;

/**
 * One selectable team on the site. Public teams anyone may join; private
 * teams (MTF and the like) demand a grant first.
 *
 * Joining a team actually DOES something now: the player is put into the
 * team's LuckPerms group ({@code rank}), and that group is (re)configured with
 * the team's chat {@code prefix} and its {@code permissions} - so a team confers
 * a rank, a prefix, and a permission set (see {@link TeamManager#applyRank}).
 * {@code icon} is what the selector paints.
 */
public record Team(String id, String display, boolean privateTeam, String rank,
                   Material icon, String prefix, List<String> permissions) {

    public Material iconOr(Material fallback) {
        return icon != null ? icon : fallback;
    }

    public boolean hasPrefix() {
        return prefix != null && !prefix.isBlank();
    }
}
