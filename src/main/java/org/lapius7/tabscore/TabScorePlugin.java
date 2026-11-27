package org.lapius7.tabscore;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

public final class TabScorePlugin extends JavaPlugin {
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private Store store;
    private Lang lang;
    private volatile Selector current;
    private volatile int afkMs = 120_000;
    private boolean excludeBots;
    private boolean excludeNoAddress;
    private Pattern botRegex;
    private java.util.Set<String> botNames = java.util.Set.of();
    private java.util.Set<String> botUuids = java.util.Set.of();
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask flushTask;
    private org.bukkit.scheduler.BukkitTask sampleTask;
    private Importer importer;
    private Metrics metrics;
    private org.bukkit.scheduler.BukkitTask metricsTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lang = new Lang(this);
        lang.load();
        readConfig();
        store = new Store(getLogger());
        try {
            getDataFolder().mkdirs();
            store.open(new File(getDataFolder(), getConfig().getString("database.file", "data.db")).toPath());
            String sel = store.getSettingSync("selection");
            Selector s = sel != null ? Selector.parse(sel) : null;
            if (s == null) s = Selector.parse(getConfig().getString("default-metric", "mine"));
            current = s != null ? s : new Selector(Cat.MINE, Cat.TOTAL, Selector.Period.TOTAL);
        } catch (Exception e) {
            getLogger().severe("Failed to open database: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        store.prune(getConfig().getInt("database.daily-retention-days", 400));
        importer = new Importer(this);

        getServer().getPluginManager().registerEvents(new Collector(this), this);
        var cmd = new Commands(this);
        var pc = getCommand("tabscore");
        pc.setExecutor(cmd);
        pc.setTabCompleter(cmd);

        long period = Math.max(5, getConfig().getInt("database.flush-interval-seconds", 30));
        flushTask = Bukkit.getAsyncScheduler().runAtFixedRate(this, t -> flushAsync(), period, period, TimeUnit.SECONDS);
        sampleTask = Bukkit.getScheduler().runTaskTimer(this, new Sampler(this)::tick, 20, 20);

        metrics = new Metrics(getConfig().getBoolean("metrics.tile-entities", true));
        metrics.update();
        long mt = Math.max(1, Math.round(getConfig().getDouble("metrics.interval-seconds", 1.0) * 20));
        metricsTask = Bukkit.getScheduler().runTaskTimer(this, metrics::update, mt, mt);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new Papi(this).register();
            getLogger().info("PlaceholderAPI expansion registered (%tabscore_value% etc.)");
        } else {
            getLogger().info("PlaceholderAPI not found; placeholders disabled.");
        }
        // 既にオンラインの人(/reload 後など)
        for (Player p : Bukkit.getOnlinePlayers()) join(p, false);
    }

    @Override
    public void onDisable() {
        if (flushTask != null) flushTask.cancel();
        if (sampleTask != null) sampleTask.cancel();
        if (metricsTask != null) metricsTask.cancel();
        if (store == null) return;
        long now = System.currentTimeMillis();
        for (PlayerData d : players.values()) store.touchPlayer(d.uuid, d.name, false, now);
        store.write(drain());
        players.clear();
        store.close();
    }

    void readConfig() {
        reloadConfig();
        var c = getConfig();
        afkMs = Math.max(5, c.getInt("afk-seconds", 120)) * 1000;
        Days.setZone(c.getString("timezone", ""));
        excludeBots = c.getBoolean("bots.exclude", true);
        excludeNoAddress = c.getBoolean("bots.exclude-no-address", true);
        String re = c.getString("bots.name-regex", "");
        botRegex = re == null || re.isBlank() ? null : Pattern.compile(re);
        botNames = new java.util.HashSet<>();
        for (String n : c.getStringList("bots.names")) botNames.add(n.toLowerCase());
        botUuids = new java.util.HashSet<>(c.getStringList("bots.uuids"));
    }

    void reload() {
        readConfig();
        lang.load();
        Selector s = Selector.parse(getConfig().getString("default-metric", "mine"));
        // 保存済みの選択が優先なので current は変えない
    }

    boolean isBotName(String name, UUID uuid) {
        if (!excludeBots) return false;
        if (botRegex != null && botRegex.matcher(name).matches()) return true;
        return botNames.contains(name.toLowerCase()) || botUuids.contains(uuid.toString());
    }

    boolean isBot(Player p) {
        if (!excludeBots) return false;
        if (isBotName(p.getName(), p.getUniqueId())) return true;
        return excludeNoAddress && p.getAddress() == null;
    }

    int afkMs() { return afkMs; }
    Store store() { return store; }
    Lang lang() { return lang; }
    Importer importer() { return importer; }
    Metrics metrics() { return metrics; }
    Selector current() { return current; }
    Map<UUID, PlayerData> players() { return players; }
    PlayerData data(Player p) { return players.get(p.getUniqueId()); }

    void setCurrent(Selector s) {
        current = s;
        store.setSetting("selection", s.toString());
    }

    void join(Player p, boolean login) {
        if (isBot(p)) return;
        PlayerData d = new PlayerData(p.getUniqueId(), p.getName());
        d.lastSampleMs = System.currentTimeMillis();
        players.put(p.getUniqueId(), d);
        if (login) {
            d.add(Cat.LOGINS, Cat.TOTAL, 1);
            store.touchPlayer(p.getUniqueId(), p.getName(), true, System.currentTimeMillis());
        } else {
            store.touchPlayer(p.getUniqueId(), p.getName(), false, System.currentTimeMillis());
        }
        store.load(d.uuid, Days.weekStart()).thenAccept(l -> {
            d.baseTotal = l.total();
            d.baseDaily = l.daily();
        });
    }

    void quit(Player p) {
        PlayerData d = players.remove(p.getUniqueId());
        if (d == null) return;
        store.touchPlayer(d.uuid, d.name, false, System.currentTimeMillis());
        store.write(drain(d));
    }

    /** 未保存の増分を取り出す(呼び出しスレッド上で確定。DB 書き込みは別スレッド)。 */
    List<Store.Row> drain() {
        List<Store.Row> rows = new ArrayList<>();
        for (PlayerData d : players.values()) drainInto(d, rows);
        return rows;
    }

    List<Store.Row> drain(PlayerData d) {
        List<Store.Row> rows = new ArrayList<>();
        drainInto(d, rows);
        return rows;
    }

    private void drainInto(PlayerData d, List<Store.Row> rows) {
        String u = d.uuid.toString();
        for (Map.Entry<String, LongAdder> e : d.pending.entrySet()) {
            long v = e.getValue().sumThenReset();
            if (v == 0) continue;
            String[] k = e.getKey().split("\\|", 3);
            rows.add(new Store.Row(u, k[0], k[1], k[2], v));
        }
    }

    java.util.concurrent.CompletableFuture<Void> flushAsync() {
        return store.write(drain());
    }

    /** 表示値(単位換算済みの整数)。未ロードのオフラインは 0。 */
    long value(UUID uuid, Selector s) {
        PlayerData d = players.get(uuid);
        if (d == null) return 0;
        return d.get(s.cat(), s.key(), s.period()) / s.cat().scale;
    }

    String label(Selector s) {
        String base = lang.plain("cat." + s.cat().id);
        if (!s.isTotalKey()) base += ": " + (s.key().startsWith("#") ? s.key().substring(1) : s.key());
        if (s.period() != Selector.Period.TOTAL) base += " (" + lang.plain("period." + s.period().name().toLowerCase()) + ")";
        return base;
    }
}
