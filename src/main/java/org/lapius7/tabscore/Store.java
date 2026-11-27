package org.lapius7.tabscore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** SQLite 保存。すべての I/O は専用の単一スレッドで直列に実行する。 */
final class Store {
    static final int SCHEMA = 1;

    record Row(String uuid, String day, String cat, String sub, long v) { }
    record Loaded(Map<String, Long> total, Map<String, Long> daily) { }
    record Rank(UUID uuid, String name, long value) { }
    record PlayerRow(UUID uuid, String name, long firstLogin, long lastLogin, long logins) { }

    private final Logger log;
    private final ExecutorService ex = Executors.newSingleThreadExecutor(r -> new Thread(r, "TabScore-DB"));
    private Connection c;

    Store(Logger log) { this.log = log; }

    void open(Path file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc not found", e);
        }
        c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=5000");
        }
        migrate();
    }

    /** スキーマ版管理。項目追加は新しい if (v < N) ブロックを足すだけ(既存データは壊さない)。 */
    private void migrate() throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)");
        }
        int v = 0;
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT v FROM meta WHERE k='schema'")) {
            if (r.next()) v = Integer.parseInt(r.getString(1));
        }
        if (v > SCHEMA) throw new SQLException("database schema " + v + " is newer than this plugin (" + SCHEMA + ")");
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            if (v < 1) {
                s.execute("CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY, name TEXT NOT NULL,"
                        + " first_login INTEGER, last_login INTEGER, last_logout INTEGER, logins INTEGER NOT NULL DEFAULT 0)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_players_name ON players(name COLLATE NOCASE)");
                s.execute("CREATE TABLE IF NOT EXISTS stat_total(uuid TEXT NOT NULL, cat TEXT NOT NULL, sub TEXT NOT NULL,"
                        + " v INTEGER NOT NULL, PRIMARY KEY(uuid, cat, sub)) WITHOUT ROWID");
                s.execute("CREATE INDEX IF NOT EXISTS idx_total_rank ON stat_total(cat, sub, v DESC)");
                s.execute("CREATE TABLE IF NOT EXISTS stat_daily(uuid TEXT NOT NULL, day TEXT NOT NULL, cat TEXT NOT NULL,"
                        + " sub TEXT NOT NULL, v INTEGER NOT NULL, PRIMARY KEY(uuid, day, cat, sub)) WITHOUT ROWID");
                s.execute("CREATE INDEX IF NOT EXISTS idx_daily_rank ON stat_daily(day, cat, sub)");
                s.execute("CREATE TABLE IF NOT EXISTS settings(k TEXT PRIMARY KEY, v TEXT)");
            }
            // if (v < 2) { ... }
            s.execute("INSERT INTO meta(k,v) VALUES('schema','" + SCHEMA + "') ON CONFLICT(k) DO UPDATE SET v=excluded.v");
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private <T> CompletableFuture<T> run(SqlTask<T> t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            ex.execute(() -> {
                try {
                    f.complete(t.run());
                } catch (Throwable e) {
                    log.warning("DB error: " + e);
                    f.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            f.completeExceptionally(e);
        }
        return f;
    }

    private interface SqlTask<T> { T run() throws Exception; }

    // ---- 設定 ----
    String getSettingSync(String k) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT v FROM settings WHERE k=?")) {
            p.setString(1, k);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    CompletableFuture<String> getSetting(String k) { return run(() -> getSettingSync(k)); }

    CompletableFuture<Void> setSetting(String k, String v) {
        return run(() -> {
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO settings(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
                p.setString(1, k);
                p.setString(2, v);
                p.executeUpdate();
            }
            return null;
        });
    }

    // ---- 書き込み ----
    CompletableFuture<Void> write(List<Row> rows) {
        if (rows.isEmpty()) return CompletableFuture.completedFuture(null);
        return run(() -> {
            Map<String, Long> tot = new HashMap<>();
            for (Row r : rows) tot.merge(r.uuid + "\0" + r.cat + "\0" + r.sub, r.v, Long::sum);
            c.setAutoCommit(false);
            try (PreparedStatement pt = c.prepareStatement(
                    "INSERT INTO stat_total(uuid,cat,sub,v) VALUES(?,?,?,?) ON CONFLICT(uuid,cat,sub) DO UPDATE SET v=v+excluded.v");
                 PreparedStatement pd = c.prepareStatement(
                         "INSERT INTO stat_daily(uuid,day,cat,sub,v) VALUES(?,?,?,?,?) ON CONFLICT(uuid,day,cat,sub) DO UPDATE SET v=v+excluded.v")) {
                for (Map.Entry<String, Long> e : tot.entrySet()) {
                    String[] k = e.getKey().split("\0");
                    pt.setString(1, k[0]); pt.setString(2, k[1]); pt.setString(3, k[2]); pt.setLong(4, e.getValue());
                    pt.addBatch();
                }
                pt.executeBatch();
                for (Row r : rows) {
                    pd.setString(1, r.uuid); pd.setString(2, r.day); pd.setString(3, r.cat); pd.setString(4, r.sub); pd.setLong(5, r.v);
                    pd.addBatch();
                }
                pd.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
            return null;
        });
    }

    /** 取り込み用: 累計テーブルにだけ加算する(日別には入れない)。 */
    CompletableFuture<Void> addTotals(UUID uuid, Map<String, Long> totals) {
        return run(() -> {
            c.setAutoCommit(false);
            try (PreparedStatement pt = c.prepareStatement(
                    "INSERT INTO stat_total(uuid,cat,sub,v) VALUES(?,?,?,?) ON CONFLICT(uuid,cat,sub) DO UPDATE SET v=v+excluded.v")) {
                for (Map.Entry<String, Long> e : totals.entrySet()) {
                    int i = e.getKey().indexOf('|');
                    pt.setString(1, uuid.toString());
                    pt.setString(2, e.getKey().substring(0, i));
                    pt.setString(3, e.getKey().substring(i + 1));
                    pt.setLong(4, e.getValue());
                    pt.addBatch();
                }
                pt.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
            return null;
        });
    }

    CompletableFuture<Void> touchPlayer(UUID uuid, String name, boolean login, long now) {
        return run(() -> {
            try (PreparedStatement p = c.prepareStatement(login
                    ? "INSERT INTO players(uuid,name,first_login,last_login,logins) VALUES(?,?,?,?,1) ON CONFLICT(uuid) DO UPDATE SET"
                      + " name=excluded.name, last_login=excluded.last_login, logins=logins+1"
                    : "INSERT INTO players(uuid,name,first_login,last_login,last_logout,logins) VALUES(?,?,?,?,?,0) ON CONFLICT(uuid) DO UPDATE SET"
                      + " name=excluded.name, last_logout=excluded.last_logout")) {
                p.setString(1, uuid.toString());
                p.setString(2, name);
                p.setLong(3, now);
                p.setLong(4, now);
                if (!login) p.setLong(5, now);
                p.executeUpdate();
            }
            return null;
        });
    }

    /** 取り込み用: 名前だけ登録(既存行は触らない)。 */
    CompletableFuture<Void> ensurePlayer(UUID uuid, String name, long lastPlayed) {
        return run(() -> {
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO players(uuid,name,first_login,last_login,logins) VALUES(?,?,?,?,0) ON CONFLICT(uuid) DO NOTHING")) {
                p.setString(1, uuid.toString());
                p.setString(2, name);
                p.setLong(3, lastPlayed);
                p.setLong(4, lastPlayed);
                p.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Void> prune(int keepDays) {
        return run(() -> {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM stat_daily WHERE day < ?")) {
                p.setString(1, java.time.LocalDate.now().minusDays(keepDays).toString());
                p.executeUpdate();
            }
            return null;
        });
    }

    // ---- 読み込み ----
    CompletableFuture<Loaded> load(UUID uuid, String weekStart) {
        return run(() -> {
            Map<String, Long> total = new HashMap<>();
            Map<String, Long> daily = new HashMap<>();
            try (PreparedStatement p = c.prepareStatement("SELECT cat,sub,v FROM stat_total WHERE uuid=?")) {
                p.setString(1, uuid.toString());
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) total.put(r.getString(1) + "|" + r.getString(2), r.getLong(3));
                }
            }
            try (PreparedStatement p = c.prepareStatement("SELECT day,cat,sub,v FROM stat_daily WHERE uuid=? AND day>=?")) {
                p.setString(1, uuid.toString());
                p.setString(2, weekStart);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) daily.put(r.getString(1) + "|" + r.getString(2) + "|" + r.getString(3), r.getLong(4));
                }
            }
            return new Loaded(total, daily);
        });
    }

    CompletableFuture<List<Rank>> top(Cat cat, String sub, Selector.Period period, int limit) {
        return run(() -> {
            String sql;
            if (period == Selector.Period.TOTAL) {
                sql = "SELECT t.uuid, COALESCE(p.name,t.uuid), t.v FROM stat_total t LEFT JOIN players p ON p.uuid=t.uuid"
                        + " WHERE t.cat=? AND t.sub=? AND t.v>0 ORDER BY t.v DESC LIMIT ?";
            } else {
                sql = "SELECT t.uuid, COALESCE(p.name,t.uuid), SUM(t.v) s FROM stat_daily t LEFT JOIN players p ON p.uuid=t.uuid"
                        + " WHERE t.cat=? AND t.sub=? AND t.day>=? GROUP BY t.uuid HAVING s>0 ORDER BY s DESC LIMIT ?";
            }
            List<Rank> out = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(sql)) {
                int i = 1;
                p.setString(i++, cat.id);
                p.setString(i++, sub);
                if (period != Selector.Period.TOTAL) p.setString(i++, period == Selector.Period.TODAY ? Days.today() : Days.weekStart());
                p.setInt(i, limit);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) out.add(new Rank(UUID.fromString(r.getString(1)), r.getString(2), r.getLong(3)));
                }
            }
            return out;
        });
    }

    CompletableFuture<PlayerRow> findPlayer(String name) {
        return run(() -> {
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT uuid,name,first_login,last_login,logins FROM players WHERE name=? COLLATE NOCASE")) {
                p.setString(1, name);
                try (ResultSet r = p.executeQuery()) {
                    return r.next() ? new PlayerRow(UUID.fromString(r.getString(1)), r.getString(2), r.getLong(3), r.getLong(4), r.getLong(5)) : null;
                }
            }
        });
    }

    CompletableFuture<List<String>> playerNames() {
        return run(() -> {
            List<String> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT name FROM players")) {
                while (r.next()) out.add(r.getString(1));
            }
            return out;
        });
    }

    CompletableFuture<Map<String, Long>> totals(UUID uuid) {
        return run(() -> {
            Map<String, Long> m = new HashMap<>();
            try (PreparedStatement p = c.prepareStatement("SELECT cat,sub,v FROM stat_total WHERE uuid=?")) {
                p.setString(1, uuid.toString());
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) m.put(r.getString(1) + "|" + r.getString(2), r.getLong(3));
                }
            }
            return m;
        });
    }

    CompletableFuture<Map<String, Long>> dailyOn(UUID uuid, String day) {
        return run(() -> {
            Map<String, Long> m = new HashMap<>();
            try (PreparedStatement p = c.prepareStatement("SELECT cat,sub,v FROM stat_daily WHERE uuid=? AND day=?")) {
                p.setString(1, uuid.toString());
                p.setString(2, day);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) m.put(r.getString(1) + "|" + r.getString(2), r.getLong(3));
                }
            }
            return m;
        });
    }

    /** 停止: 溜まった処理を全部流してから閉じる。 */
    void close() {
        ex.shutdown();
        try {
            if (!ex.awaitTermination(30, TimeUnit.SECONDS)) log.warning("DB writer did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (c != null) c.close();
        } catch (SQLException ignored) { }
    }
}
