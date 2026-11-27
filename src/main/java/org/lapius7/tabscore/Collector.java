package org.lapius7.tabscore;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.Material;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** イベントをメモリ上で集計するだけ(I/O は一切しない)。 */
final class Collector implements Listener {
    private final TabScorePlugin plugin;

    Collector(TabScorePlugin plugin) { this.plugin = plugin; }

    private PlayerData d(Entity e) {
        return e instanceof Player p ? plugin.data(p) : null;
    }

    private static String key(Material m) { return m.key().value(); }

    private static String key(Entity e) { return e.getType().key().value(); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { plugin.join(e.getPlayer(), true); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { plugin.quit(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d != null) d.lastLoc = null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d == null) return;
        d.touch();
        d.add(Cat.MINE, key(e.getBlock().getType()), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d == null) return;
        d.touch();
        d.add(Cat.PLACE, key(e.getBlockPlaced().getType()), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        if (v instanceof Player p) {
            PlayerData d = d(p);
            if (d != null) {
                EntityDamageEvent last = p.getLastDamageCause();
                d.add(Cat.DEATHS, last == null ? "unknown" : last.getCause().name().toLowerCase(Locale.ROOT), 1);
            }
        }
        Player k = v.getKiller();
        PlayerData kd = d(k);
        if (kd == null || k == v) return;
        kd.add(Cat.KILL, key(v), 1);
        String group = v instanceof Player ? "#player" : v instanceof Enemy ? "#monster" : v instanceof Animals ? "#animal" : "#other";
        kd.addSubOnly(Cat.KILL, group, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        long tenths = Math.round(e.getFinalDamage() * 10);
        if (tenths <= 0) return;
        PlayerData victim = d(e.getEntity());
        if (victim != null) victim.add(Cat.DAMAGE_TAKEN, e.getCause().name().toLowerCase(Locale.ROOT), tenths);
        if (e instanceof EntityDamageByEntityEvent be) {
            Entity src = be.getDamager();
            if (src instanceof Projectile pr && pr.getShooter() instanceof Entity s) src = s;
            PlayerData dealer = d(src);
            if (dealer != null && src != e.getEntity()) {
                dealer.touch();
                dealer.add(Cat.DAMAGE_DEALT, key(e.getEntity()), tenths);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        PlayerData d = d(e.getPlayer());
        if (d == null) return;
        String k = e.getCaught() instanceof org.bukkit.entity.Item it ? key(it.getItemStack().getType()) : "unknown";
        d.add(Cat.FISH, k, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        PlayerData d = d(e.getBreeder());
        if (d != null) d.add(Cat.BREED, key(e.getEntity()), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d != null) d.add(Cat.TRADE, Cat.TOTAL, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d == null) return;
        d.touch();
        Action a = e.getAction();
        if ((a == Action.RIGHT_CLICK_BLOCK || a == Action.RIGHT_CLICK_AIR) && e.useItemInHand() != Event.Result.DENY) {
            ItemStack it = e.getItem();
            if (it != null && !it.getType().isAir()) d.add(Cat.USE, key(it.getType()), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        PlayerData d = d(e.getWhoClicked());
        if (d == null) return;
        ItemStack res = e.getRecipe().getResult();
        long times = 1;
        if (e.isShiftClick()) {
            int min = Integer.MAX_VALUE;
            for (ItemStack s : e.getInventory().getMatrix()) {
                if (s != null && !s.getType().isAir()) min = Math.min(min, s.getAmount());
            }
            if (min != Integer.MAX_VALUE) times = min;
        }
        d.add(Cat.CRAFT, key(res.getType()), times * res.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d == null) return;
        d.touch();
        ItemStack s = e.getItemDrop().getItemStack();
        d.add(Cat.DROP, key(s.getType()), s.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        PlayerData d = d(e.getEntity());
        if (d == null) return;
        ItemStack s = e.getItem().getItemStack();
        d.add(Cat.PICKUP, key(s.getType()), s.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d == null) return;
        d.touch();
        d.add(Cat.CONSUME, key(e.getItem().getType()), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemBreak(PlayerItemBreakEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d != null) d.add(Cat.ITEM_BREAK, key(e.getBrokenItem().getType()), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d == null) return;
        d.touch();
        d.add(Cat.CHAT, Cat.TOTAL, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d == null) return;
        d.touch();
        d.add(Cat.COMMAND, Cat.TOTAL, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJump(PlayerJumpEvent e) {
        PlayerData d = d(e.getPlayer());
        if (d == null) return;
        d.touch();
        d.add(Cat.JUMP, Cat.TOTAL, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        PlayerData d = d(e.getEnchanter());
        if (d != null) d.add(Cat.ENCHANT, Cat.TOTAL, 1);
    }
}
