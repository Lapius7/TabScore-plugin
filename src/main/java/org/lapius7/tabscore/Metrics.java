package org.lapius7.tabscore;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** サーバー負荷の集計。主スレッドで定期的に計算して volatile に保持し、どのスレッドからでも読める。 */
final class Metrics {
    private volatile Map<String, String> values = Map.of();
    private final boolean tiles;
    private final com.sun.management.OperatingSystemMXBean os;

    Metrics(boolean tiles) {
        this.tiles = tiles;
        var b = ManagementFactory.getOperatingSystemMXBean();
        this.os = b instanceof com.sun.management.OperatingSystemMXBean x ? x : null;
    }

    String get(String key) { return values.get(key.toLowerCase(Locale.ROOT)); }

    /** 主スレッドから呼ぶ。 */
    void update() {
        Map<String, String> m = new HashMap<>();
        long ent = 0, living = 0, tile = 0, chunks = 0, forced = 0;
        for (World w : Bukkit.getWorlds()) {
            int e = w.getEntityCount();
            int c = w.getChunkCount();
            ent += e;
            chunks += c;
            living += w.getLivingEntities().size();
            forced += w.getForceLoadedChunks().size();
            if (tiles) for (Chunk ch : w.getLoadedChunks()) tile += ch.getTileEntities(false).length;
            String n = w.getName().toLowerCase(Locale.ROOT);
            m.put("entities_" + n, Integer.toString(e));
            m.put("chunks_" + n, Integer.toString(c));
        }
        m.put("entities", Long.toString(ent));
        m.put("living", Long.toString(living));
        m.put("tiles", Long.toString(tile));
        m.put("chunks", Long.toString(chunks));
        m.put("forced", Long.toString(forced));
        m.put("players", Integer.toString(Bukkit.getOnlinePlayers().size()));
        double[] tps = Bukkit.getTPS();
        m.put("tps1", one(Math.min(20, tps[0])));
        m.put("tps5", one(Math.min(20, tps[1])));
        m.put("tps15", one(Math.min(20, tps[2])));
        m.put("mspt", one(Bukkit.getAverageTickTime()));
        double cpu = os == null ? -1 : os.getProcessCpuLoad();
        m.put("cpu", cpu < 0 ? "0" : one(cpu * 100));
        Runtime r = Runtime.getRuntime();
        long mb = 1024 * 1024;
        m.put("ram_used", Long.toString((r.totalMemory() - r.freeMemory()) / mb));
        m.put("ram_alloc", Long.toString(r.totalMemory() / mb));
        m.put("ram_max", Long.toString(r.maxMemory() / mb));
        values = m;
    }

    private static String one(double d) { return String.format(Locale.ROOT, "%.1f", d); }
}
