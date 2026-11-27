package org.lapius7.tabscore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class Lang {
    private final TabScorePlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private YamlConfiguration y = new YamlConfiguration();

    Lang(TabScorePlugin plugin) { this.plugin = plugin; }

    void load() {
        File f = new File(plugin.getDataFolder(), "lang.yml");
        if (!f.exists()) plugin.saveResource("lang.yml", false);
        y = YamlConfiguration.loadConfiguration(f);
        var in = plugin.getResource("lang.yml");
        if (in != null) y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    String raw(String key) {
        String s = y.getString(key);
        return s == null ? key : s;
    }

    /** MiniMessage タグ無しのプレーン文字列(ラベル・単位用)。 */
    String plain(String key) { return raw(key); }

    Component get(String key, TagResolver... r) {
        return mm.deserialize(raw(key), TagResolver.builder()
                .resolver(Placeholder.parsed("prefix", raw("prefix")))
                .resolvers(r).build());
    }

    void send(CommandSender to, String key, TagResolver... r) { to.sendMessage(get(key, r)); }

    static TagResolver p(String name, Object v) { return Placeholder.unparsed(name, String.valueOf(v)); }
}
