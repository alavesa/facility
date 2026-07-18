package fi.alavesa.facility;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One operator-editable entry in the MAIN menu dialog. Two kinds:
 *   BUTTON - a clickable action button that runs {@link #action} (a command
 *            with NO leading slash) on click; {@link #label} is its face.
 *   TEXT   - a plain body line; {@link #action} is unused.
 *
 * These live in config.yml under {@code menu.elements} and are round-tripped
 * as plain maps by {@link MenuStore}. Labels carry legacy '&' colour codes.
 */
public record MenuElement(Type type, String label, String action) {

    public enum Type { BUTTON, TEXT }

    public static MenuElement button(String label, String action) {
        return new MenuElement(Type.BUTTON, label == null ? "" : label,
            action == null ? "" : action);
    }

    public static MenuElement text(String label) {
        return new MenuElement(Type.TEXT, label == null ? "" : label, "");
    }

    /** Parse a config map, tolerating junk (returns null so a malformed entry
     *  is skipped rather than crashing dialog building). */
    public static MenuElement fromMap(Map<?, ?> map) {
        if (map == null) return null;
        Object rawType = map.get("type");
        String typeStr = rawType == null ? "text" : rawType.toString().toLowerCase(Locale.ROOT);
        Object label = map.get("label");
        Object action = map.get("action");
        String labelStr = label == null ? "" : label.toString();
        String actionStr = action == null ? "" : action.toString();
        if ("button".equals(typeStr)) return button(labelStr, actionStr);
        if ("text".equals(typeStr)) return text(labelStr);
        return null;   // unknown type: skip it
    }

    /** Back to a plain map for config persistence. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type == Type.BUTTON ? "button" : "text");
        map.put("label", label);
        if (type == Type.BUTTON) map.put("action", action);
        return map;
    }
}
