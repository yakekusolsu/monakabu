package jp.monakaserver.monakabu.message;

import jp.monakaserver.monakabu.config.ConfigManager;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

public final class MessageService {
    private final ConfigManager configs;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MessageService(ConfigManager configs) {
        this.configs = configs;
    }

    public Component component(String key, Map<String, String> placeholders) {
        String value = configs.messages().getString(key, "<red>Missing message: " + key);
        TagResolver.Builder resolver = TagResolver.builder();
        placeholders.forEach((name, text) -> resolver.resolver(Placeholder.unparsed(name, text)));
        return miniMessage.deserialize(value, resolver.build());
    }

    public Component component(String key) { return component(key, Map.of()); }

    public Component raw(String miniMessageText) { return miniMessage.deserialize(miniMessageText); }

    public void send(CommandSender sender, String key) { send(sender, key, Map.of()); }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        String prefix = configs.messages().getString("prefix", "");
        String value = configs.messages().getString(key, "<red>Missing message: " + key);
        TagResolver.Builder resolver = TagResolver.builder();
        placeholders.forEach((name, text) -> resolver.resolver(Placeholder.unparsed(name, text)));
        sender.sendMessage(miniMessage.deserialize(prefix + value, resolver.build()));
    }
}
