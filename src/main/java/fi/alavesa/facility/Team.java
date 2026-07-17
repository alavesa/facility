package fi.alavesa.facility;

import org.bukkit.Material;

/**
 * One selectable team on the site. Public teams anyone may join; private
 * teams (MTF and the like) demand a grant first. rank is the LuckPerms group
 * we set the player into; icon is what the selector paints.
 */
public record Team(String id, String display, boolean privateTeam, String rank, Material icon) {

    /** The legacy-coded display string, defaulting nicely if someone typos the material. */
    public Material iconOr(Material fallback) {
        return icon != null ? icon : fallback;
    }
}
