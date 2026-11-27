package org.lapius7.tabscore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Strider;

/** 毎秒(メインスレッド)プレイヤーの位置と状態を見て、移動距離と稼働時間を集計する。 */
final class Sampler {
    private static final double MAX_STEP = 200; // これを超える移動はテレポート扱いで無視

    private final TabScorePlugin plugin;

    Sampler(TabScorePlugin plugin) { this.plugin = plugin; }

    void tick() {
        Days.refresh();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data(p);
            if (d == null) continue;
            Location l = p.getLocation();
            Location prev = d.lastLoc;
            d.lastLoc = l;

            boolean moved = false;
            if (prev != null && prev.getWorld() == l.getWorld()) {
                double dx = l.getX() - prev.getX(), dy = l.getY() - prev.getY(), dz = l.getZ() - prev.getZ();
                double h = Math.sqrt(dx * dx + dz * dz);
                double full = Math.sqrt(h * h + dy * dy);
                moved = full > 0.05 || Math.abs(l.getYaw() - prev.getYaw()) > 1 || Math.abs(l.getPitch() - prev.getPitch()) > 1;
                if (full > 0.01 && full < MAX_STEP) distance(p, d, h, dy, full);
            } else if (prev != null) {
                moved = true;
            }
            if (moved) d.touch();

            long el = now - d.lastSampleMs + d.carryMs;
            d.lastSampleMs = now;
            long sec = el / 1000;
            d.carryMs = el % 1000;
            if (sec > 0 && sec < 600) {
                boolean afk = now - d.lastActiveMs > plugin.afkMs();
                d.add(Cat.PLAYTIME, afk ? "afk" : "active", sec);
            }
        }
    }

    private void distance(Player p, PlayerData d, double h, double dy, double full) {
        Entity v = p.getVehicle();
        String m;
        double dist = h;
        if (v != null) {
            m = v instanceof Boat ? "boat" : v instanceof Minecart ? "minecart" : v instanceof AbstractHorse ? "horse"
                    : v instanceof Pig ? "pig" : v instanceof Strider ? "strider" : "vehicle";
        } else if (p.isGliding()) {
            m = "elytra"; dist = full;
        } else if (p.isFlying()) {
            m = "fly"; dist = full;
        } else if (p.isInWater()) {
            m = "swim";
        } else if (p.isClimbing()) {
            m = "climb"; dist = Math.abs(dy);
        } else if (p.isSneaking()) {
            m = "sneak";
        } else if (p.isSprinting()) {
            m = "sprint";
        } else {
            m = "walk";
        }
        if (dist > 0) d.add(Cat.DISTANCE, m, Math.round(dist * 100));
        if (v == null && dy < 0 && !p.isGliding() && !p.isFlying() && !p.isInWater() && !p.isClimbing() && p.getFallDistance() > 0) {
            d.addSubOnly(Cat.DISTANCE, "fall", Math.round(-dy * 100));
        }
    }
}
