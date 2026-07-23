package fi.alavesa.facility;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Actionbar bridge. When Labra is present, messages route through its ActionBars
 * hub (via reflection - no compile dependency) so they compose into the SAME
 * frame as the blink/sprint meters instead of the two fighting over the single
 * vanilla actionbar line (which is what made the /menu countdown flicker with
 * the meters). Without Labra, plain sendActionBar. Resolved once, not per tick.
 */
final class Msg {

    private static final Method HUB_MESSAGE;
    private static final Method HUB_CROSSHAIR;
    private static final Method HUB_CLEAR_CROSSHAIR;

    static {
        Method m = null, ch = null, cc = null;
        try {
            if (Bukkit.getPluginManager().getPlugin("Labra") != null) {
                Class<?> hub = Class.forName("fi.alavesa.labra.ActionBars");
                m = hub.getMethod("message", Player.class, Component.class);
                ch = hub.getMethod("crosshair", Player.class, Component.class, int.class);
                cc = hub.getMethod("clearCrosshair", Player.class);
            }
        } catch (ReflectiveOperationException ignored) {
            m = null; ch = null; cc = null;
        }
        HUB_MESSAGE = m;
        HUB_CROSSHAIR = ch;
        HUB_CLEAR_CROSSHAIR = cc;
    }

    private Msg() { }

    static void actionbar(Player player, Component text) {
        if (HUB_MESSAGE != null) {
            try {
                HUB_MESSAGE.invoke(null, player, text);
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through to the vanilla path
            }
        }
        player.sendActionBar(text);
    }

    /** Show the interact-crosshair through Labra's compositor so it never fights the
     *  blink/sprint meters or a held gun's reticle. Falls back to a raw action bar. */
    static void crosshair(Player player, Component glyph, int width) {
        if (HUB_CROSSHAIR != null) {
            try {
                HUB_CROSSHAIR.invoke(null, player, glyph, width);
                return;
            } catch (ReflectiveOperationException ignored) { }
        }
        player.sendActionBar(glyph);
    }

    static void clearCrosshair(Player player) {
        if (HUB_CLEAR_CROSSHAIR != null) {
            try {
                HUB_CLEAR_CROSSHAIR.invoke(null, player);
                return;
            } catch (ReflectiveOperationException ignored) { }
        }
        player.sendActionBar(Component.empty());
    }
}
