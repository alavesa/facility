package fi.alavesa.facility;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The main menu's audio/visual settings ({@code menu.shader}, {@code
 * menu.transitions}, {@code menu.music}), read ONCE per load / {@code /facility
 * reload} into an immutable snapshot. Everything is validated here - a bad
 * sound key, an unknown look or a negative duration is logged and replaced with
 * something safe - so {@link MenuAudioAndVisualPacketHandler} never has to
 * second-guess a value mid-animation.
 *
 * Dialog TEXT is not in here: {@link DialogMenu} reads {@code menu.text} live,
 * like {@link MenuStore} does for the element list.
 */
public record MenuSettings(Shader shader, Transitions transitions, Music music) {

    /** The looks tools/gen_menu_fx.py ships. Another name is allowed (an admin
     *  may have generated their own) but gets a warning. */
    public static final List<String> LOOKS = List.of("green", "amber", "nvg", "mono");

    /** {@code look} is the middle of the post-effect ids: facility:menu_&lt;look&gt;... */
    public record Shader(boolean enabled, String look) {}

    /** Durations are in ticks; 0 skips that animation. {@code glitch} picks
     *  the out/intro frames with static + CRT power-off over the plain fade. */
    public record Transitions(boolean enabled, boolean glitch, int introTicks, int navigateTicks,
                              int deployTicks, int holdTicks, int revealTicks,
                              Key bootSound, Key clickSound, Key glitchSound, Key deploySound,
                              float volume) {}

    public record Track(Key sound, double lengthSeconds) {}

    public record Music(boolean enabled, List<Track> tracks, boolean shuffle, double overlapSeconds,
                        float volume, float pitch, Sound.Source category) {}

    public static MenuSettings load(FacilityPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        Logger log = plugin.getLogger();

        String look = cfg.getString("menu.shader.look", "green").trim().toLowerCase(Locale.ROOT);
        if (!look.matches("[a-z0-9_]+")) {
            log.warning("menu.shader.look '" + look + "' is not a valid name - using green.");
            look = "green";
        } else if (!LOOKS.contains(look)) {
            log.warning("menu.shader.look '" + look + "' is not a built-in look " + LOOKS
                + " - it only works if your resource pack has facility:menu_" + look + " post effects.");
        }
        Shader shader = new Shader(cfg.getBoolean("menu.shader.enabled", true), look);

        String style = cfg.getString("menu.transitions.style", "glitch").trim().toLowerCase(Locale.ROOT);
        if (!style.equals("glitch") && !style.equals("fade")) {
            log.warning("menu.transitions.style '" + style + "' must be glitch or fade - using glitch.");
            style = "glitch";
        }
        Transitions transitions = new Transitions(
            cfg.getBoolean("menu.transitions.enabled", true), style.equals("glitch"),
            ticks(cfg, log, "menu.transitions.intro-ticks", 24),
            ticks(cfg, log, "menu.transitions.navigate-ticks", 4),
            ticks(cfg, log, "menu.transitions.deploy-ticks", 24),
            ticks(cfg, log, "menu.transitions.hold-ticks", 8),
            ticks(cfg, log, "menu.transitions.reveal-ticks", 16),
            sound(cfg, log, "menu.transitions.sounds.boot"),
            sound(cfg, log, "menu.transitions.sounds.click"),
            sound(cfg, log, "menu.transitions.sounds.glitch"),
            sound(cfg, log, "menu.transitions.sounds.deploy"),
            (float) Math.max(0.0, cfg.getDouble("menu.transitions.sounds.volume", 1.0)));

        return new MenuSettings(shader, transitions, music(cfg, log));
    }

    private static Music music(FileConfiguration cfg, Logger log) {
        List<Track> tracks = new ArrayList<>();
        // A config.yml from before 0.19 has menu.music.sound + interval-minutes and no track list.
        // contains(path, true) ignores the jar defaults, so that old setting still wins over them.
        if (!cfg.contains("menu.music.tracks", true) && cfg.contains("menu.music.sound", true)) {
            Key key = sound(cfg, log, "menu.music.sound");
            if (key != null) tracks.add(new Track(key, cfg.getDouble("menu.music.interval-minutes", 0.5) * 60.0));
        } else {
            for (Map<?, ?> raw : cfg.getMapList("menu.music.tracks")) {
                Object name = raw.get("sound");
                Object length = raw.get("length-seconds");
                Key key = key(log, "menu.music.tracks", name == null ? "" : name.toString());
                if (key == null) continue;
                if (!(length instanceof Number n) || n.doubleValue() < 1.0) {
                    log.warning("menu.music.tracks: '" + name + "' needs length-seconds (the .ogg's length, at least 1)"
                        + " - skipped.");
                    continue;
                }
                tracks.add(new Track(key, n.doubleValue()));
            }
        }

        String categoryName = cfg.getString("menu.music.category", "music").trim().toUpperCase(Locale.ROOT);
        Sound.Source category;
        try {
            category = Sound.Source.valueOf(categoryName);
        } catch (IllegalArgumentException unknown) {
            log.warning("menu.music.category '" + categoryName + "' is not a sound category - using music.");
            category = Sound.Source.MUSIC;
        }
        float pitch = (float) cfg.getDouble("menu.music.pitch", 1.0);
        if (pitch < 0.5f || pitch > 2.0f) {
            log.warning("menu.music.pitch must be 0.5-2.0 - using 1.0.");
            pitch = 1.0f;
        }
        return new Music(cfg.getBoolean("menu.music.enabled", true), List.copyOf(tracks),
            "shuffle".equalsIgnoreCase(cfg.getString("menu.music.order", "sequence")),
            Math.max(0.0, cfg.getDouble("menu.music.overlap-seconds", 0.0)),
            (float) Math.max(0.0, cfg.getDouble("menu.music.volume", 1.0)), pitch, category);
    }

    private static int ticks(FileConfiguration cfg, Logger log, String path, int def) {
        int value = cfg.getInt(path, def);
        if (value < 0 || value > 200) {
            log.warning(path + " must be 0-200 ticks - using " + def + ".");
            return def;
        }
        return value;
    }

    /** A sound key from config; blank = no sound (null), invalid = warned + null. */
    private static Key sound(FileConfiguration cfg, Logger log, String path) {
        return key(log, path, cfg.getString(path, ""));
    }

    @SuppressWarnings("PatternValidation")
    private static Key key(Logger log, String path, String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Key.key(name.trim());
        } catch (Exception invalid) {
            log.warning(path + ": '" + name + "' is not a valid sound key - ignored.");
            return null;
        }
    }
}
