package org.lapius7.tabscore;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 日付キー(yyyy-MM-dd)。毎秒 refresh される。 */
final class Days {
    private static volatile ZoneId zone = ZoneId.systemDefault();
    private static volatile String today = LocalDate.now().toString();
    private static volatile List<String> week = List.of(today);

    private Days() { }

    static void setZone(String id) {
        try {
            zone = (id == null || id.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(id);
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        refresh();
    }

    static void refresh() {
        LocalDate d = LocalDate.now(zone);
        String t = d.toString();
        if (t.equals(today)) return;
        List<String> w = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) w.add(d.minusDays(i).toString());
        week = List.copyOf(w);
        today = t;
    }

    static String today() { return today; }

    /** 今日を含む直近 7 日。 */
    static List<String> week() {
        List<String> w = week;
        if (!w.get(0).equals(today)) refresh();
        return week;
    }

    static String weekStart() { List<String> w = week(); return w.get(w.size() - 1); }

    static {
        LocalDate d = LocalDate.now(zone);
        List<String> w = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) w.add(d.minusDays(i).toString());
        week = List.copyOf(w);
    }
}
