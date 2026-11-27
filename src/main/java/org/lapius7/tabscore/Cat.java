package org.lapius7.tabscore;

import java.util.List;
import java.util.Locale;

/** 統計カテゴリ。scale は内部値→表示値の割り算、unit は lang の unit.* キー。 */
enum Cat {
    MINE("mine", 1, "count", Kind.BLOCK),
    PLACE("place", 1, "count", Kind.BLOCK),
    KILL("kill", 1, "count", Kind.ENTITY),
    DEATHS("deaths", 1, "count", Kind.CAUSE),
    DAMAGE_DEALT("damage_dealt", 10, "hp", Kind.ENTITY),
    DAMAGE_TAKEN("damage_taken", 10, "hp", Kind.CAUSE),
    DISTANCE("distance", 100, "m", Kind.FIXED, "walk", "sprint", "sneak", "swim", "fly", "elytra", "boat",
            "horse", "minecart", "pig", "strider", "vehicle", "climb", "fall"),
    PLAYTIME("playtime", 60, "min", Kind.FIXED, "active", "afk", "imported"),
    LOGINS("logins", 1, "count", Kind.NONE),
    FISH("fish", 1, "count", Kind.ITEM),
    BREED("breed", 1, "count", Kind.ENTITY),
    TRADE("trade", 1, "count", Kind.NONE),
    USE("use", 1, "count", Kind.ITEM),
    CRAFT("craft", 1, "count", Kind.ITEM),
    DROP("drop", 1, "count", Kind.ITEM),
    PICKUP("pickup", 1, "count", Kind.ITEM),
    CONSUME("consume", 1, "count", Kind.ITEM),
    ITEM_BREAK("item_break", 1, "count", Kind.ITEM),
    CHAT("chat", 1, "count", Kind.NONE),
    COMMAND("command", 1, "count", Kind.NONE),
    JUMP("jump", 1, "count", Kind.NONE),
    ENCHANT("enchant", 1, "count", Kind.NONE);

    enum Kind { BLOCK, ENTITY, ITEM, CAUSE, FIXED, NONE }

    static final String TOTAL = "*";

    final String id;
    final long scale;
    final String unit;
    final Kind kind;
    final List<String> fixed;

    Cat(String id, long scale, String unit, Kind kind, String... fixed) {
        this.id = id;
        this.scale = scale;
        this.unit = unit;
        this.kind = kind;
        this.fixed = List.of(fixed);
    }

    static Cat of(String s) {
        s = s.toLowerCase(Locale.ROOT);
        for (Cat c : values()) if (c.id.equals(s)) return c;
        return switch (s) {
            case "mined", "break", "dig" -> MINE;
            case "placed" -> PLACE;
            case "kills", "mobkill" -> KILL;
            case "death" -> DEATHS;
            case "dist" -> DISTANCE;
            case "time", "play" -> PLAYTIME;
            case "login" -> LOGINS;
            case "fishing" -> FISH;
            default -> null;
        };
    }
}
