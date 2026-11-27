package org.lapius7.tabscore;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** %tabscore_value% / %tabscore_label% / %tabscore_unit% / %tabscore_<項目>%。メモリのみ参照(ブロックしない)。 */
final class Papi extends PlaceholderExpansion {
    private final TabScorePlugin plugin;

    Papi(TabScorePlugin plugin) { this.plugin = plugin; }

    @Override public @NotNull String getIdentifier() { return "tabscore"; }
    @Override public @NotNull String getAuthor() { return "Lapius7"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        switch (params.toLowerCase()) {
            case "label": return plugin.label(plugin.current());
            case "selector": return plugin.current().toString();
            case "unit": return plugin.lang().plain("unit." + plugin.current().cat().unit);
            default: break;
        }
        if (player == null) return "0";
        Selector s = params.equalsIgnoreCase("value") ? plugin.current() : Selector.parseLoose(params);
        if (s == null) return null;
        return Long.toString(plugin.value(player.getUniqueId(), s));
    }
}
