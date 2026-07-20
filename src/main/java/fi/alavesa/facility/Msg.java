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

    static {
        Method m = null;
        try {
            if (Bukkit.getPluginManager().getPlugin("Labra") != null) {
                m = Class.forName("fi.alavesa.labra.ActionBars")
                    .getMethod("message", Player.class, Component.class);
            }
        } catch (ReflectiveOperationException ignored) {
            m = null;
        }
        HUB_MESSAGE = m;
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
}
