package org.lapius7.tabscore;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Locale;

/** `cat[:key][:today|:week]` の項目指定。 */
record Selector(Cat cat, String key, Period period) {
    enum Period { TOTAL, TODAY, WEEK }

    static final String[] GROUPS = {"player", "monster", "animal", "other"};

    static Selector parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        String[] t = s.split(":", -1);
        int n = t.length;
        Period p = Period.TOTAL;
        if (n > 1) {
            switch (t[n - 1]) {
                case "today" -> { p = Period.TODAY; n--; }
                case "week" -> { p = Period.WEEK; n--; }
                case "total", "all" -> { n--; }
                default -> { }
            }
        }
        Cat c = Cat.of(t[0]);
        if (c == null) return null;
        String key = Cat.TOTAL;
        if (n > 1) {
            key = String.join(":", java.util.Arrays.copyOfRange(t, 1, n));
            if (key.startsWith("minecraft:")) key = key.substring(10);
            if (key.isEmpty() || key.equals("*") || key.equals("total")) key = Cat.TOTAL;
        }
        if (!key.equals(Cat.TOTAL)) {
            switch (c.kind) {
                case BLOCK -> { if (Material.matchMaterial(key) == null) return null; }
                case ITEM -> { if (Material.matchMaterial(key) == null) return null; }
                case ENTITY -> {
                    if (c == Cat.KILL && isGroup(key)) key = "#" + key;
                    else if (!key.startsWith("#") && !validEntity(key)) return null;
                }
                case FIXED -> { if (!c.fixed.contains(key)) return null; }
                case NONE -> { return null; }
                default -> { }
            }
        }
        return new Selector(c, key, p);
    }

    /** PlaceholderAPI 用: `mine_diamond_ore_today` のような '_' 区切りも受ける。 */
    static Selector parseLoose(String raw) {
        Selector s = parse(raw);
        if (s != null || raw == null) return s;
        String r = raw.toLowerCase(Locale.ROOT);
        String period = "";
        for (String p : new String[]{"today", "week"}) {
            if (r.endsWith("_" + p)) { period = ":" + p; r = r.substring(0, r.length() - p.length() - 1); }
        }
        Cat best = null;
        for (Cat c : Cat.values()) {
            if (r.equals(c.id) || r.startsWith(c.id + "_")) {
                if (best == null || c.id.length() > best.id.length()) best = c;
            }
        }
        if (best == null) return null;
        String rest = r.substring(best.id.length());
        if (rest.startsWith("_")) rest = ":" + rest.substring(1);
        return parse(best.id + rest + period);
    }

    private static boolean isGroup(String k) {
        for (String g : GROUPS) if (g.equals(k)) return true;
        return false;
    }

    private static boolean validEntity(String key) {
        try {
            return EntityType.valueOf(key.toUpperCase(Locale.ROOT)) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    boolean isTotalKey() { return key.equals(Cat.TOTAL); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(cat.id);
        if (!isTotalKey()) sb.append(':').append(key.startsWith("#") ? key.substring(1) : key);
        if (period == Period.TODAY) sb.append(":today");
        else if (period == Period.WEEK) sb.append(":week");
        return sb.toString();
    }
}
