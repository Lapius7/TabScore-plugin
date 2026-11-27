package org.lapius7.tabscore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** オンラインプレイヤー 1 人分のメモリ上の集計。DB 読み込み値(base)+参加後の増分。 */
final class PlayerData {
    final UUID uuid;
    volatile String name;
    /** 参加時に DB から読んだ累計 "cat|sub" と直近日別 "day|cat|sub" */
    volatile Map<String, Long> baseTotal = Map.of();
    volatile Map<String, Long> baseDaily = Map.of();
    final ConcurrentHashMap<String, LongAdder> total = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, LongAdder> daily = new ConcurrentHashMap<>();
    /** まだ DB に書いていない増分 "day|cat|sub" */
    final ConcurrentHashMap<String, LongAdder> pending = new ConcurrentHashMap<>();

    // サンプリング用(メインスレッドのみ)
    org.bukkit.Location lastLoc;
    long lastSampleMs;
    long carryMs;
    volatile long lastActiveMs = System.currentTimeMillis();

    PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    void touch() { lastActiveMs = System.currentTimeMillis(); }

    /** カテゴリ総計("*")にも加算する。 */
    void add(Cat cat, String sub, long delta) {
        if (delta == 0) return;
        addRaw(cat.id, sub, delta);
        if (!Cat.TOTAL.equals(sub)) addRaw(cat.id, Cat.TOTAL, delta);
    }

    /** 総計には足さず、サブキーだけに加算する(グループ集計・落下距離など)。 */
    void addSubOnly(Cat cat, String sub, long delta) {
        if (delta != 0) addRaw(cat.id, sub, delta);
    }

    private void addRaw(String cat, String sub, long delta) {
        String day = Days.today();
        String ts = cat + "|" + sub;
        total.computeIfAbsent(ts, k -> new LongAdder()).add(delta);
        String ds = day + "|" + ts;
        daily.computeIfAbsent(ds, k -> new LongAdder()).add(delta);
        pending.computeIfAbsent(ds, k -> new LongAdder()).add(delta);
    }

    long get(Cat cat, String sub, Selector.Period period) {
        String ts = cat.id + "|" + sub;
        return switch (period) {
            case TOTAL -> baseTotal.getOrDefault(ts, 0L) + sum(total.get(ts));
            case TODAY -> day(Days.today(), ts);
            case WEEK -> {
                long v = 0;
                for (String d : Days.week()) v += day(d, ts);
                yield v;
            }
        };
    }

    private long day(String day, String ts) {
        String ds = day + "|" + ts;
        return baseDaily.getOrDefault(ds, 0L) + sum(daily.get(ds));
    }

    private static long sum(LongAdder a) { return a == null ? 0 : a.sum(); }
}
