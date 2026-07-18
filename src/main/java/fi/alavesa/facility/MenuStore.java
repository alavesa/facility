package fi.alavesa.facility;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The operator-editable MAIN menu contents, backed by {@code menu.elements} in
 * config.yml. Both the command editor (/facility menu ...) and the chest+anvil
 * GUI editor mutate through here, and every mutation persists to config and is
 * reflected the next time the dialog is built (DialogMenu reads live from
 * config, so no cache to invalidate).
 *
 * If config carries no elements at all we fall back to the two default buttons
 * so the menu is never empty out of the box.
 */
public final class MenuStore {

    private final FacilityPlugin plugin;

    public MenuStore(FacilityPlugin plugin) {
        this.plugin = plugin;
    }

    /** The current element list, parsed live from config. Malformed entries are
     *  skipped (never crash dialog building). Empty config -> the defaults. */
    public List<MenuElement> elements() {
        List<MenuElement> out = new ArrayList<>();
        List<Map<?, ?>> raw = plugin.getConfig().getMapList("menu.elements");
        for (Map<?, ?> map : raw) {
            MenuElement el = MenuElement.fromMap(map);
            if (el != null) out.add(el);
        }
        if (out.isEmpty()) {
            out.add(MenuElement.button("&a&l▶ PLAY", "facility continue"));
            out.add(MenuElement.button("&bSELECT TEAM", "facility teams"));
        }
        return out;
    }

    /** Persist a new element list to config. */
    public void save(List<MenuElement> elements) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (MenuElement el : elements) raw.add(el.toMap());
        plugin.getConfig().set("menu.elements", raw);
        plugin.saveConfig();
    }

    // --- mutations (all validate + persist) ---------------------------------

    public void add(MenuElement element) {
        List<MenuElement> els = elements();
        els.add(element);
        save(els);
    }

    /** Remove by index. Returns the removed element, or null if out of range. */
    public MenuElement remove(int index) {
        List<MenuElement> els = elements();
        if (index < 0 || index >= els.size()) return null;
        MenuElement removed = els.remove(index);
        save(els);
        return removed;
    }

    /** Move an element up (-1) or down (+1). Returns true if it moved. */
    public boolean move(int index, int delta) {
        List<MenuElement> els = elements();
        int target = index + delta;
        if (index < 0 || index >= els.size() || target < 0 || target >= els.size()) return false;
        MenuElement el = els.remove(index);
        els.add(target, el);
        save(els);
        return true;
    }

    /** Replace an element's label, keeping its type/action. Returns true on hit. */
    public boolean setLabel(int index, String label) {
        List<MenuElement> els = elements();
        if (index < 0 || index >= els.size()) return false;
        MenuElement el = els.get(index);
        els.set(index, new MenuElement(el.type(), label, el.action()));
        save(els);
        return true;
    }

    /** Replace a BUTTON's action. Returns false if out of range or not a button. */
    public boolean setAction(int index, String action) {
        List<MenuElement> els = elements();
        if (index < 0 || index >= els.size()) return false;
        MenuElement el = els.get(index);
        if (el.type() != MenuElement.Type.BUTTON) return false;
        els.set(index, new MenuElement(el.type(), el.label(), action));
        save(els);
        return true;
    }

    public int size() {
        return elements().size();
    }
}
