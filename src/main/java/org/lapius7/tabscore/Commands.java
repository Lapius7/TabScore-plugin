package org.lapius7.tabscore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.lapius7.tabscore.Lang.p;

final class Commands implements TabExecutor {
    private static final List<String> SUBS = List.of("change", "me", "top", "info", "reload", "import");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TabScorePlugin plugin;

    Commands(TabScorePlugin plugin) { this.plugin = plugin; }

    private Lang lang() { return plugin.lang(); }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("tabscore.admin")) return true;
        lang().send(s, "no-permission");
        return false;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String label, String @NotNull [] a) {
        if (a.length == 0) { lang().send(s, "usage"); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "change" -> change(s, a);
            case "me" -> { if (use(s)) me(s, a); }
            case "top" -> { if (use(s)) top(s, a); }
            case "info" -> { if (use(s)) info(s, a); }
            case "reload" -> {
                if (!admin(s)) return true;
                plugin.reload();
                lang().send(s, "reloaded");
            }
            case "import" -> doImport(s, a);
            default -> lang().send(s, "usage");
        }
        return true;
    }

    private boolean use(CommandSender s) {
        if (s.hasPermission("tabscore.use")) return true;
        lang().send(s, "no-permission");
        return false;
    }

    private void change(CommandSender s, String[] a) {
        if (!admin(s)) return;
        if (a.length < 2) { lang().send(s, "usage"); return; }
        Selector sel = Selector.parse(a[1]);
        if (sel == null) { lang().send(s, "unknown-metric", p("input", a[1])); return; }
        plugin.setCurrent(sel);
        lang().send(s, "changed", p("label", plugin.label(sel)), p("selector", sel));
    }

    private void me(CommandSender s, String[] a) {
        if (!(s instanceof Player pl)) { lang().send(s, "players-only"); return; }
        Selector sel = plugin.current();
        if (a.length >= 2) {
            sel = Selector.parse(a[1]);
            if (sel == null) { lang().send(s, "unknown-metric", p("input", a[1])); return; }
        }
        lang().send(s, "me", p("label", plugin.label(sel)), p("value", plugin.value(pl.getUniqueId(), sel)),
                p("unit", lang().plain("unit." + sel.cat().unit)));
    }

    private void top(CommandSender s, String[] a) {
        Selector sel = a.length >= 2 ? Selector.parse(a[1]) : plugin.current();
        if (sel == null) { lang().send(s, "unknown-metric", p("input", a[1])); return; }
        final Selector fs = sel;
        plugin.flushAsync();
        plugin.store().top(sel.cat(), sel.key(), sel.period(), 10).whenComplete((rows, err) -> sync(() -> {
            if (err != null) { lang().send(s, "db-error"); return; }
            lang().send(s, "top-header", p("label", plugin.label(fs)), p("unit", lang().plain("unit." + fs.cat().unit)));
            if (rows.isEmpty()) lang().send(s, "top-empty");
            int i = 1;
            for (Store.Rank r : rows) {
                lang().send(s, "top-line", p("rank", i++), p("name", r.name()), p("value", r.value() / fs.cat().scale));
            }
        }));
    }

    private void info(CommandSender s, String[] a) {
        String name = a.length >= 2 ? a[1] : (s instanceof Player pl ? pl.getName() : null);
        if (name == null) { lang().send(s, "usage"); return; }
        plugin.flushAsync();
        plugin.store().findPlayer(name).thenCompose(row -> {
            if (row == null) return java.util.concurrent.CompletableFuture.completedFuture(new Object[]{null, null, null});
            return plugin.store().totals(row.uuid()).thenCombine(plugin.store().dailyOn(row.uuid(), Days.today()),
                    (t, dd) -> new Object[]{row, t, dd});
        }).whenComplete((res, err) -> sync(() -> {
            if (err != null) { lang().send(s, "db-error"); return; }
            if (res[0] == null) { lang().send(s, "unknown-player", p("input", name)); return; }
            @SuppressWarnings("unchecked") Map<String, Long> t = (Map<String, Long>) res[1];
            @SuppressWarnings("unchecked") Map<String, Long> td = (Map<String, Long>) res[2];
            Store.PlayerRow row = (Store.PlayerRow) res[0];
            lang().send(s, "info-header", p("name", row.name()));
            for (Cat c : Cat.values()) {
                long v = t.getOrDefault(c.id + "|*", 0L);
                if (v == 0) continue;
                lang().send(s, "info-line", p("label", lang().plain("cat." + c.id)), p("value", v / c.scale),
                        p("unit", lang().plain("unit." + c.unit)), p("today", td.getOrDefault(c.id + "|*", 0L) / c.scale));
                if (c.kind == Cat.Kind.NONE) continue;
                List<Map.Entry<String, Long>> subs = new ArrayList<>();
                for (Map.Entry<String, Long> e : t.entrySet()) {
                    if (e.getKey().startsWith(c.id + "|") && !e.getKey().endsWith("|*") && e.getValue() > 0) subs.add(e);
                }
                subs.sort(Map.Entry.<String, Long>comparingByValue().reversed());
                if (subs.isEmpty()) continue;
                StringBuilder sb = new StringBuilder();
                int n = c == Cat.DISTANCE || c == Cat.PLAYTIME ? 6 : 3;
                for (int i = 0; i < Math.min(n, subs.size()); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(subs.get(i).getKey().substring(c.id.length() + 1)).append(' ').append(subs.get(i).getValue() / c.scale);
                }
                lang().send(s, "info-sub", p("top", sb));
            }
            lang().send(s, "info-last", p("time", row.lastLogin() == 0 ? "-" : TIME.format(Instant.ofEpochMilli(row.lastLogin()).atZone(ZoneId.systemDefault()))),
                    p("logins", row.logins()));
        }));
    }

    private void doImport(CommandSender s, String[] a) {
        if (!admin(s)) return;
        if (!(s instanceof ConsoleCommandSender) && !s.isOp()) { lang().send(s, "import-op-only"); return; }
        boolean confirm = a.length >= 2 && a[1].equalsIgnoreCase("confirm");
        plugin.importer().start(s, confirm);
    }

    void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }

    // ---- タブ補完 ----
    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String label, String @NotNull [] a) {
        if (a.length == 1) return filter(SUBS.stream().filter(x -> allowed(s, x)).toList(), a[0]);
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            switch (sub) {
                case "change", "top", "me" -> { return completeSelector(a[1]); }
                case "info" -> {
                    List<String> n = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) n.add(p.getName());
                    for (OfflinePlayer p : Bukkit.getOfflinePlayers()) if (p.getName() != null) n.add(p.getName());
                    return filter(n.stream().distinct().toList(), a[1]);
                }
                case "import" -> { return filter(List.of("confirm"), a[1]); }
                default -> { }
            }
        }
        return List.of();
    }

    private boolean allowed(CommandSender s, String sub) {
        return switch (sub) {
            case "change", "reload", "import" -> s.hasPermission("tabscore.admin");
            default -> s.hasPermission("tabscore.use");
        };
    }

    private static List<String> filter(List<String> all, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String x : all) if (x.toLowerCase(Locale.ROOT).startsWith(p)) out.add(x);
        return out.size() > 300 ? out.subList(0, 300) : out;
    }

    private static final List<String> PERIODS = List.of("today", "week");

    /** base → base:key → base:key:today の段階補完。 */
    private List<String> completeSelector(String arg) {
        String lower = arg.toLowerCase(Locale.ROOT);
        int first = lower.indexOf(':');
        if (first < 0) {
            List<String> bases = new ArrayList<>();
            for (Cat c : Cat.values()) bases.add(c.id);
            return filter(bases, lower);
        }
        Cat cat = Cat.of(lower.substring(0, first));
        if (cat == null) return List.of();
        String rest = lower.substring(first + 1);
        String head = lower.substring(0, first + 1);
        List<String> out = new ArrayList<>();
        int second = rest.lastIndexOf(':');
        if (second >= 0) {
            // base:key:<period>
            String keyPart = rest.substring(0, second);
            String pre = head + keyPart + ":";
            for (String p : PERIODS) out.add(pre + p);
            return filter(out, lower);
        }
        for (String p : PERIODS) out.add(head + p);
        for (String k : keys(cat)) out.add(head + k);
        return filter(out, lower);
    }

    private static List<String> blocks;
    private static List<String> items;
    private static List<String> entities;

    private static List<String> keys(Cat cat) {
        return switch (cat.kind) {
            case BLOCK -> {
                if (blocks == null) {
                    List<String> l = new ArrayList<>();
                    for (Material m : Material.values()) if (!m.isLegacy() && m.isBlock()) l.add(m.key().value());
                    l.sort(Comparator.naturalOrder());
                    blocks = l;
                }
                yield blocks;
            }
            case ITEM -> {
                if (items == null) {
                    List<String> l = new ArrayList<>();
                    for (Material m : Material.values()) if (!m.isLegacy() && m.isItem()) l.add(m.key().value());
                    l.sort(Comparator.naturalOrder());
                    items = l;
                }
                yield items;
            }
            case ENTITY -> {
                if (entities == null) {
                    List<String> l = new ArrayList<>();
                    for (EntityType t : EntityType.values()) if (t != EntityType.UNKNOWN) l.add(t.key().value());
                    l.sort(Comparator.naturalOrder());
                    entities = l;
                }
                if (cat != Cat.KILL) yield entities;
                List<String> l = new ArrayList<>(List.of(Selector.GROUPS));
                l.addAll(entities);
                yield l;
            }
            case CAUSE -> {
                List<String> l = new ArrayList<>();
                for (var c : org.bukkit.event.entity.EntityDamageEvent.DamageCause.values()) l.add(c.name().toLowerCase(Locale.ROOT));
                yield l;
            }
            case FIXED -> cat.fixed;
            case NONE -> List.<String>of();
        };
    }
}
