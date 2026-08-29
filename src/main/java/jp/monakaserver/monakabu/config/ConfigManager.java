package jp.monakaserver.monakabu.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigManager {
    private static final List<String> FILES = List.of("config.yml", "stocks.yml", "events.yml", "gui.yml", "messages.yml");
    private final JavaPlugin plugin;
    private volatile YamlConfiguration config;
    private volatile YamlConfiguration stocks;
    private volatile YamlConfiguration events;
    private volatile YamlConfiguration gui;
    private volatile YamlConfiguration messages;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create plugin data directory");
        }
        for (String name : FILES) {
            File file = new File(plugin.getDataFolder(), name);
            if (!file.exists()) plugin.saveResource(name, false);
        }
        YamlConfiguration newConfig = loadStrict("config.yml", true);
        YamlConfiguration newStocks = loadStrict("stocks.yml", false);
        YamlConfiguration newEvents = loadStrict("events.yml", false);
        YamlConfiguration newGui = loadStrict("gui.yml", true);
        YamlConfiguration newMessages = loadStrict("messages.yml", true);
        config = newConfig;
        stocks = newStocks;
        events = newEvents;
        gui = newGui;
        messages = newMessages;
    }

    private YamlConfiguration loadStrict(String name,boolean mergeDefaults) {
        File file = new File(plugin.getDataFolder(), name);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            boolean changed=false;
            if(mergeDefaults){try(InputStream stream=plugin.getResource(name)){if(stream!=null){YamlConfiguration defaults=YamlConfiguration.loadConfiguration(new InputStreamReader(stream,StandardCharsets.UTF_8));for(String key:defaults.getKeys(true)){if(defaults.isConfigurationSection(key))continue;if(!yaml.contains(key)){yaml.set(key,defaults.get(key));changed=true;}}}}}
            if(name.equals("messages.yml")){for(String key:yaml.getKeys(true)){Object value=yaml.get(key);if(value instanceof String text&&text.contains("円")){yaml.set(key,text.replace("円"," MONA"));changed=true;}}}
            if(changed)yaml.save(file);
            return yaml;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, name + " の読み込みに失敗しました", exception);
            throw new IllegalArgumentException("Invalid " + name, exception);
        }
    }

    public YamlConfiguration config() { return Objects.requireNonNull(config); }
    public YamlConfiguration stocks() { return Objects.requireNonNull(stocks); }
    public YamlConfiguration events() { return Objects.requireNonNull(events); }
    public YamlConfiguration gui() { return Objects.requireNonNull(gui); }
    public YamlConfiguration messages() { return Objects.requireNonNull(messages); }

    public void saveStocks() {
        try {
            stocks().save(new File(plugin.getDataFolder(), "stocks.yml"));
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "stocks.yml を保存できませんでした", exception);
        }
    }
}
