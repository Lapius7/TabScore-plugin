package org.lapius7.tabscore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lapius7.tabscore.Lang.p;

/**
 * 本体の統計(world/players/stats/<uuid>.json)を累計へ 1 回だけ取り込む。
 * ファイルの読み込み・解析・DB 書き込みはすべて非同期。完了マークは取り込みと同じトランザクションの最後。
 */
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
            begin(s);
        }));
    }

    private void begin(CommandSender s) {
        // オンライン中の人の stats を最新にする(少人数のみ。ここ以外で主スレッドは使わない)
        try { Bukkit.savePlayers(); } catch (Exception e) { plugin.getLogger().warning("savePlayers: " + e); }
        java.util.Map<String, File> byName = new java.util.LinkedHashMap<>();
        for (File dir : statsDirs()) {
            File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (fs != null) for (File f : fs) byName.putIfAbsent(f.getName(), f);
        }
        File[] files = byName.values().toArray(new File[0]);
        // 名前の解決はキャッシュ参照のみ(軽い)。ファイル数が多くても getStatistic は呼ばない
        List<Object[]> targets = new ArrayList<>();
        {
            for (File f : files) {
                UUID u;
                try { u = UUID.fromString(f.getName().substring(0, f.getName().length() - 5)); }
                catch (IllegalArgumentException e) { continue; }
                String name = Bukkit.getOfflinePlayer(u).getName();
                if (name == null) name = u.toString().substring(0, 8);
                if (plugin.isBotName(name, u)) continue;
                targets.add(new Object[]{f, u, name});
            }
        }
        int total = targets.size();
        plugin.lang().send(s, "import-start", p("count", total));
        Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            try {
                List<Store.ImportPlayer> list = new ArrayList<>();
                int[] last = {0};
                for (Object[] tg : targets) {
                    File f = (File) tg[0];
                    try {
                        list.add(new Store.ImportPlayer((UUID) tg[1], (String) tg[2], f.lastModified(), parse(f)));
                    } catch (Exception e) {
                        plugin.getLogger().warning("import: skip " + f.getName() + ": " + e);
                    }
                }
                plugin.store().importAll(list, FLAG, Long.toString(System.currentTimeMillis()), n -> {
                    if (n - last[0] >= 50 || n == list.size()) {
                        last[0] = n;
                        Bukkit.getScheduler().runTask(plugin, () -> plugin.lang().send(s, "import-progress", p("done", n), p("total", list.size())));
                    }
                }).whenComplete((ok, err) -> {
                    running.set(false);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (err != null) plugin.lang().send(s, "db-error");
                        else if (!ok) plugin.lang().send(s, "import-already", p("time", "-"));
                        else plugin.lang().send(s, "import-done", p("done", list.size()));
                    });
                });
            } catch (Throwable e) {
                running.set(false);
                plugin.getLogger().severe("import failed: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> plugin.lang().send(s, "db-error"));
            }
        });
    }

    /** 本体の統計ディレクトリ候補(ワールドフォルダ配下とレベル名フォルダ配下の両方)。 */
    private List<File> statsDirs() {
        List<File> out = new ArrayList<>();
        List<File> bases = new ArrayList<>();
        for (org.bukkit.World w : Bukkit.getWorlds()) bases.add(w.getWorldFolder());
        bases.add(new File(Bukkit.getWorldContainer(), Bukkit.getWorlds().get(0).getName()));
        for (File wf : bases) {
            for (String rel : new String[]{"players/stats", "stats"}) {
                File d = new File(wf, rel);
                if (d.isDirectory() && !out.contains(d)) out.add(d);
            }
        }
        return out;
    }

    private static void put(Map<String, Long> m, Cat c, String sub, long v) {
        if (v <= 0) return;
        m.merge(c.id + "|" + sub, v, Long::sum);
        if (!sub.equals(Cat.TOTAL)) m.merge(c.id + "|" + Cat.TOTAL, v, Long::sum);
    }

    private static final Map<String, String> DIST = Map.ofEntries(
            Map.entry("walk_one_cm", "walk"), Map.entry("walk_on_water_one_cm", "walk"),
            Map.entry("sprint_one_cm", "sprint"), Map.entry("crouch_one_cm", "sneak"),
            Map.entry("swim_one_cm", "swim"), Map.entry("walk_under_water_one_cm", "swim"),
            Map.entry("fly_one_cm", "fly"), Map.entry("aviate_one_cm", "elytra"),
            Map.entry("boat_one_cm", "boat"), Map.entry("horse_one_cm", "horse"),
            Map.entry("minecart_one_cm", "minecart"), Map.entry("pig_one_cm", "pig"),
            Map.entry("strider_one_cm", "strider"), Map.entry("climb_one_cm", "climb"));

    static Map<String, Long> parse(File f) throws Exception {
        JsonObject stats;
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            stats = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("stats");
        }
        Map<String, Long> m = new HashMap<>();
        if (stats == null) return m;
        for (Map.Entry<String, JsonElement> cat : stats.entrySet()) {
            if (!cat.getValue().isJsonObject()) continue;
            String type = strip(cat.getKey());
            for (Map.Entry<String, JsonElement> e : cat.getValue().getAsJsonObject().entrySet()) {
                long v = e.getValue().getAsLong();
                String k = strip(e.getKey());
                switch (type) {
                    case "mined" -> put(m, Cat.MINE, k, v);
                    // 本体に「置いた数」は無いので、ブロックの「使用数」を place の近似にする
                    case "used" -> put(m, isBlock(k) ? Cat.PLACE : Cat.USE, k, v);
                    case "crafted" -> put(m, Cat.CRAFT, k, v);
                    case "broken" -> put(m, Cat.ITEM_BREAK, k, v);
                    case "dropped" -> put(m, Cat.DROP, k, v);
                    case "picked_up" -> put(m, Cat.PICKUP, k, v);
                    case "killed" -> put(m, Cat.KILL, k, v);
                    case "custom" -> custom(m, k, v);
                    default -> { }
                }
            }
        }
        return m;
    }

    private static boolean isBlock(String key) {
        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(key);
        return mat != null && mat.isBlock();
    }

    private static void custom(Map<String, Long> m, String k, long v) {
        String d = DIST.get(k);
        if (d != null) { put(m, Cat.DISTANCE, d, v); return; }
        switch (k) {
            case "fall_one_cm" -> { if (v > 0) m.merge("distance|fall", v, Long::sum); }
            case "play_time", "play_one_minute" -> put(m, Cat.PLAYTIME, "imported", v / 20);
            case "fish_caught" -> put(m, Cat.FISH, Cat.TOTAL, v);
            case "animals_bred" -> put(m, Cat.BREED, Cat.TOTAL, v);
            case "traded_with_villager" -> put(m, Cat.TRADE, Cat.TOTAL, v);
            case "jump" -> put(m, Cat.JUMP, Cat.TOTAL, v);
            case "leave_game" -> put(m, Cat.LOGINS, Cat.TOTAL, v);
            case "deaths" -> put(m, Cat.DEATHS, "imported", v);
            case "damage_dealt" -> put(m, Cat.DAMAGE_DEALT, "imported", v);
            case "damage_taken" -> put(m, Cat.DAMAGE_TAKEN, "imported", v);
            case "player_kills" -> m.merge("kill|#player", v, Long::sum);
            case "mob_kills" -> m.merge("kill|#monster", v, Long::sum); // 種別の区別が無いので monster に寄せる
            default -> { }
        }
    }

    private static String strip(String s) {
        return (s.startsWith("minecraft:") ? s.substring(10) : s).toLowerCase(Locale.ROOT);
    }
}
