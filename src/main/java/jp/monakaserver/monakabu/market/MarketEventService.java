package jp.monakaserver.monakabu.market;

import jp.monakaserver.monakabu.api.event.MarketEventEndEvent;
import jp.monakaserver.monakabu.api.event.MarketEventStartEvent;
import jp.monakaserver.monakabu.config.ConfigManager;
import jp.monakaserver.monakabu.database.DatabaseManager;
import jp.monakaserver.monakabu.database.repository.MarketEventRepository;
import jp.monakaserver.monakabu.message.MessageService;
import jp.monakaserver.monakabu.model.ActiveMarketEvent;
import jp.monakaserver.monakabu.model.MarketEventDefinition;
import jp.monakaserver.monakabu.model.Season;
import jp.monakaserver.monakabu.util.DurationParser;
import jp.monakaserver.monakabu.util.MainThread;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public final class MarketEventService {
    private final JavaPlugin plugin; private final ConfigManager configs; private final DatabaseManager database;
    private final MarketEventRepository repository; private final MessageService messages; private final Supplier<Season> seasonSupplier;
    private final Map<String, MarketEventDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, ActiveMarketEvent> active = new ConcurrentHashMap<>();

    public MarketEventService(JavaPlugin plugin, ConfigManager configs, DatabaseManager database, MarketEventRepository repository,
                              MessageService messages, Supplier<Season> seasonSupplier) {
        this.plugin=plugin; this.configs=configs; this.database=database; this.repository=repository; this.messages=messages; this.seasonSupplier=seasonSupplier;
    }

    public void reloadDefinitions() {
        ConfigurationSection root = configs.events().getConfigurationSection("events");
        Map<String, MarketEventDefinition> loaded = new ConcurrentHashMap<>();
        if (root != null) for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id); if (section == null) continue;
            loaded.put(id, new MarketEventDefinition(id, section.getString("stock", ""), section.getString("name", id),
                    section.getDouble("modifier", 1), DurationParser.parse(section.getString("duration", "30m")),
                    Math.max(1, section.getInt("weight", 1)), section.getString("message", id)));
        }
        definitions.clear(); definitions.putAll(loaded);
    }

    public void restore() {
        Season season = seasonSupplier.get(); if (season == null) return;
        database.read(c -> repository.restore(c, season.id(), Instant.now(), definitions)).thenAccept(events -> {
            active.clear(); for (ActiveMarketEvent event : events) active.put(event.instanceId(), event);
        }).exceptionally(error -> { plugin.getLogger().log(Level.SEVERE, "Market events could not be restored", error); return null; });
    }

    public void randomCheck() {
        if (!configs.config().getBoolean("events.enabled", true)) return;
        if (ThreadLocalRandom.current().nextDouble() >= configs.config().getDouble("events.chance-per-check", .15)) return;
        weightedRandom().ifPresent(def -> start(def.id()));
    }

    private Optional<MarketEventDefinition> weightedRandom() {
        ArrayList<MarketEventDefinition> candidates = new ArrayList<>(definitions.values());
        candidates.removeIf(def -> active.values().stream().anyMatch(a -> a.definition().stockId().equals(def.stockId())));
        int total = candidates.stream().mapToInt(MarketEventDefinition::weight).sum(); if (total <= 0) return Optional.empty();
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (MarketEventDefinition candidate : candidates) { roll -= candidate.weight(); if (roll < 0) return Optional.of(candidate); }
        return Optional.empty();
    }

    public boolean start(String eventId) {
        MarketEventDefinition definition = definitions.get(eventId); Season season = seasonSupplier.get();
        if (definition == null || season == null || season.status() != jp.monakaserver.monakabu.model.MarketStatus.OPEN) return false;
        Instant now = Instant.now();
        ActiveMarketEvent event = new ActiveMarketEvent("EVT-" + java.util.UUID.randomUUID(), definition, now, now.plus(definition.duration()));
        database.transaction(c -> { repository.start(c, event, season.id()); return null; }).thenRun(() -> {
            active.put(event.instanceId(), event);
            MainThread.run(plugin, () -> { Bukkit.getPluginManager().callEvent(new MarketEventStartEvent(event)); Bukkit.broadcast(messages.raw(definition.message())); });
        }).exceptionally(error -> { plugin.getLogger().log(Level.SEVERE, "Market event start failed", error); return null; });
        return true;
    }

    public boolean startForStock(String stockId, String eventId) {
        MarketEventDefinition definition = definitions.get(eventId);
        return definition != null && definition.stockId().equals(stockId) && start(eventId);
    }

    public void expire(Instant now) {
        Season season = seasonSupplier.get(); if (season == null) return;
        ArrayList<ActiveMarketEvent> expired = new ArrayList<>();
        active.values().removeIf(event -> { if (!event.activeAt(now)) { expired.add(event); return true; } return false; });
        if (expired.isEmpty()) return;
        database.transaction(c -> repository.endExpired(c, season.id(), now)).thenRun(() -> MainThread.run(plugin,
                () -> expired.forEach(event -> Bukkit.getPluginManager().callEvent(new MarketEventEndEvent(event)))))
                .exceptionally(error -> { plugin.getLogger().log(Level.WARNING, "Expired events could not be persisted", error); return null; });
    }

    public double factorFor(String stockId, Duration updateInterval, Instant now) {
        double factor = 1;
        for (ActiveMarketEvent event : active.values()) if (event.activeAt(now) && event.definition().stockId().equals(stockId)) {
            double fraction = Math.min(1, (double) updateInterval.toMillis() / event.definition().duration().toMillis());
            factor *= Math.exp(Math.log(event.definition().modifier()) * fraction);
        }
        return factor;
    }

    public Collection<ActiveMarketEvent> activeEvents() { return Collections.unmodifiableCollection(new ArrayList<>(active.values())); }

    public void endSeason(long seasonId) {
        Instant now=Instant.now(); active.clear();
        database.transaction(c -> repository.endAll(c, seasonId, now)).exceptionally(error -> { plugin.getLogger().log(Level.WARNING,"Events could not be ended",error); return null; });
    }
}
