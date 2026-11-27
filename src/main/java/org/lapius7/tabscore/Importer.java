package org.lapius7.tabscore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lapius7.tabscore.Lang.p;

/** 本体の統計(getStatistic)を累計へ 1 回だけ取り込む。メインスレッドで 1 tick 1 人ずつ。 */
final class Importer {
    private static final String FLAG = "imported_at";
    private final TabScorePlugin plugin;
    private final AtomicBoolean running = new AtomicBoolean();

    Importer(TabScorePlugin plugin) { this.plugin = plugin; }

    void start(CommandSender s, boolean confirm) {
        Lang lang = plugin.lang();
        if (running.get()) { lang.send(s, "import-running"); return; }
        plugin.store().getSetting(FLAG).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) { lang.send(s, "db-error"); return; }
            if (v != null) {
                lang.send(s, "import-already", p("time", Instant.ofEpochMilli(Long.parseLong(v))));
                return;
            }
            if (!confirm) { lang.send(s, "import-confirm"); return; }
            if (!running.compareAndSet(false, true)) { lang.send(s, "import-running"); return; }
            // 先にフラグを立てる(途中で止まっても二重取り込みにしない)
            plugin.store().setSetting(FLAG, Long.toString(System.currentTimeMillis())).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> run(s)));
        }));
    }

    private void run(CommandSender s) {
        Queue<OfflinePlayer> q = new ArrayDeque<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() == null || plugin.isBotName(op.getName(), op.getUniqueId())) continue;
            q.add(op);
        }
        int total = q.size();
        plugin.lang().send(s, "import-start", p("count", total));
        int[] done = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            for (int i = 0; i < 2 && !q.isEmpty(); i++) {
                OfflinePlayer op = q.poll();
                try {
                    Map<String, Long> m = collect(op);
                    plugin.store().ensurePlayer(op.getUniqueId(), op.getName(), op.getLastSeen());
                    if (!m.isEmpty()) plugin.store().addTotals(op.getUniqueId(), m);
                } catch (Exception e) {
                    plugin.getLogger().warning("import failed for " + op.getName() + ": " + e);
                }
                done[0]++;
            }
            if (done[0] % 50 == 0 && !q.isEmpty()) plugin.lang().send(s, "import-progress", p("done", done[0]), p("total", total));
            if (q.isEmpty()) {
                task.cancel();
                running.set(false);
                plugin.store().getSetting(FLAG).thenRun(() -> Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.lang().send(s, "import-done", p("done", done[0]))));
            }
        }, 1, 1);
    }

    private static void put(Map<String, Long> m, Cat c, String sub, long v) {
        if (v <= 0) return;
        m.merge(c.id + "|" + sub, v, Long::sum);
        if (!sub.equals(Cat.TOTAL)) m.merge(c.id + "|" + Cat.TOTAL, v, Long::sum);
    }

    private static long stat(OfflinePlayer p, Statistic st) {
        try { return p.getStatistic(st); } catch (Exception e) { return 0; }
    }

    private static long stat(OfflinePlayer p, Statistic st, Material m) {
        try { return p.getStatistic(st, m); } catch (Exception e) { return 0; }
    }

    private static long stat(OfflinePlayer p, Statistic st, EntityType t) {
        try { return p.getStatistic(st, t); } catch (Exception e) { return 0; }
    }

    private static void dist(Map<String, Long> m, OfflinePlayer p, String name, String sub, boolean inTotal) {
        try {
            long v = stat(p, Statistic.valueOf(name));
            if (v <= 0) return;
            if (inTotal) put(m, Cat.DISTANCE, sub, v);
            else m.merge(Cat.DISTANCE.id + "|" + sub, v, Long::sum);
        } catch (IllegalArgumentException ignored) { }
    }

    static Map<String, Long> collect(OfflinePlayer p) {
        Map<String, Long> m = new HashMap<>();
        for (Material mat : Material.values()) {
            if (mat.isLegacy()) continue;
            String k = mat.key().value();
            if (mat.isBlock()) put(m, Cat.MINE, k, stat(p, Statistic.MINE_BLOCK, mat));
            if (mat.isItem()) {
                // 本体に「置いた数」は無いので、ブロックの「使用数」を place の近似として使う
                long use = stat(p, Statistic.USE_ITEM, mat);
                put(m, mat.isBlock() ? Cat.PLACE : Cat.USE, k, use);
                put(m, Cat.CRAFT, k, stat(p, Statistic.CRAFT_ITEM, mat));
                put(m, Cat.ITEM_BREAK, k, stat(p, Statistic.BREAK_ITEM, mat));
                put(m, Cat.DROP, k, stat(p, Statistic.DROP, mat));
                put(m, Cat.PICKUP, k, stat(p, Statistic.PICKUP, mat));
            }
        }
        for (EntityType t : EntityType.values()) {
            if (t == EntityType.UNKNOWN) continue;
            put(m, Cat.KILL, t.key().value(), stat(p, Statistic.KILL_ENTITY, t));
        }
        long pk = stat(p, Statistic.PLAYER_KILLS), mk = stat(p, Statistic.MOB_KILLS);
        if (pk > 0) m.merge("kill|#player", pk, Long::sum);
        if (mk > 0) m.merge("kill|#monster", mk, Long::sum); // 本体の MOB_KILLS は敵対/動物の区別が無いので monster に寄せる
        long deaths = stat(p, Statistic.DEATHS);
        put(m, Cat.DEATHS, "imported", deaths);
        long dd = stat(p, Statistic.DAMAGE_DEALT), dt = stat(p, Statistic.DAMAGE_TAKEN);
        put(m, Cat.DAMAGE_DEALT, "imported", dd);
        put(m, Cat.DAMAGE_TAKEN, "imported", dt);
        dist(m, p, "WALK_ONE_CM", "walk", true);
        dist(m, p, "SPRINT_ONE_CM", "sprint", true);
        dist(m, p, "CROUCH_ONE_CM", "sneak", true);
        dist(m, p, "SWIM_ONE_CM", "swim", true);
        dist(m, p, "WALK_UNDER_WATER_ONE_CM", "swim", true);
        dist(m, p, "WALK_ON_WATER_ONE_CM", "walk", true);
        dist(m, p, "FLY_ONE_CM", "fly", true);
        dist(m, p, "AVIATE_ONE_CM", "elytra", true);
        dist(m, p, "BOAT_ONE_CM", "boat", true);
        dist(m, p, "HORSE_ONE_CM", "horse", true);
        dist(m, p, "MINECART_ONE_CM", "minecart", true);
        dist(m, p, "PIG_ONE_CM", "pig", true);
        dist(m, p, "STRIDER_ONE_CM", "strider", true);
        dist(m, p, "CLIMB_ONE_CM", "climb", true);
        dist(m, p, "FALL_ONE_CM", "fall", false);
        put(m, Cat.PLAYTIME, "imported", stat(p, Statistic.PLAY_ONE_MINUTE) / 20); // ticks → 秒
        put(m, Cat.FISH, Cat.TOTAL, stat(p, Statistic.FISH_CAUGHT));
        put(m, Cat.BREED, Cat.TOTAL, stat(p, Statistic.ANIMALS_BRED));
        put(m, Cat.TRADE, Cat.TOTAL, stat(p, Statistic.TRADED_WITH_VILLAGER));
        put(m, Cat.JUMP, Cat.TOTAL, stat(p, Statistic.JUMP));
        put(m, Cat.LOGINS, Cat.TOTAL, stat(p, Statistic.LEAVE_GAME));
        return m;
    }
}
