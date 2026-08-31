package jp.monakaserver.monakabu.realtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import jp.monakaserver.monakabu.config.ConfigManager;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Reflective optional bridge so MonaKabu remains loadable without MonaPrice. */
public final class MonaPriceRealtimeBridge implements AutoCloseable {
    private static final String UPDATE_EVENT = "jp.monakaserver.monaprice.api.event.MarketUpdateEvent";
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private final RealtimeService realtime;
    private final Listener listener = new Listener() {};
    private Object api;
    private Plugin provider;

    public MonaPriceRealtimeBridge(JavaPlugin plugin, ConfigManager configs, RealtimeService realtime) {
        this.plugin = plugin;
        this.configs = configs;
        this.realtime = realtime;
    }

    public void start() {
        close();
        if (!configs.config().getBoolean("realtime.monaprice-enabled", true)) return;
        if (!realtime.isEnabled()) return;
        provider = plugin.getServer().getPluginManager().getPlugin("MonaPrice");
        if (provider == null || !provider.isEnabled()) {
            plugin.getLogger().info("MonaPrice realtime bridge disabled (MonaPrice not found)");
            provider = null;
            return;
        }
        try {
            api = provider.getClass().getMethod("getAPI").invoke(null);
            Class<?> rawEvent = Class.forName(UPDATE_EVENT, false, provider.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(rawEvent)) throw new IllegalStateException("MarketUpdateEvent is not a Bukkit event");
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) rawEvent;
            plugin.getServer().getPluginManager().registerEvent(eventClass, listener, EventPriority.MONITOR,
                    (ignored, event) -> publishSnapshot(), plugin, true);
            publishSnapshot();
            plugin.getLogger().info("MonaPrice realtime bridge enabled for " + provider.getPluginMeta().getVersion());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            api = null;
            HandlerList.unregisterAll(listener);
            plugin.getLogger().log(Level.WARNING, "MonaPrice realtime bridge is incompatible; MonaKabu will continue", root(error));
        }
    }

    public void reload() {
        start();
    }

    public void publishSnapshot() {
        Object currentApi = api;
        if (currentApi == null) return;
        try {
            Collection<?> snapshots = collection(invoke(currentApi, "getItems"));
            ArrayList<Map<String, Object>> items = new ArrayList<>(snapshots.size());
            for (Object snapshot : snapshots) items.add(itemData(currentApi, snapshot));
            items.sort(Comparator.comparing(value -> String.valueOf(value.get("id"))));

            Object index = invoke(currentApi, "getMarketIndex");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("currency", optionalString(currentApi, "getCurrency", "MONA"));
            data.put("index", indexData(index));
            data.put("nextUpdateSeconds", number(invoke(currentApi, "getSecondsUntilNextUpdate")).longValue());
            data.put("capturedAt", Instant.now().toString());
            data.put("sourceVersion", provider == null ? "unknown" : provider.getPluginMeta().getVersion());
            data.put("items", items);
            realtime.publishExternal("monaprice.snapshot", data);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "MonaPrice snapshot could not be published", root(error));
        }
    }

    private Map<String, Object> itemData(Object currentApi, Object snapshot) throws ReflectiveOperationException {
        Object material = invoke(snapshot, "material");
        String id = ((Enum<?>) material).name();
        double price = number(invoke(snapshot, "currentPrice")).doubleValue();
        double previous = number(invoke(snapshot, "previousPrice")).doubleValue();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("displayName", optionalApiString(currentApi, "getDisplayName", material, pretty(id)));
        item.put("category", optionalApiString(currentApi, "getCategory", material, "other"));
        item.put("categoryName", optionalApiString(currentApi, "getCategoryDisplayName", material, "その他"));
        item.put("price", price);
        item.put("previousPrice", previous);
        item.put("changePercent", previous <= 0 ? 0 : (price / previous - 1) * 100);
        item.put("buyPrice", optionalApiNumber(currentApi, "getBuyPrice", material, price));
        item.put("sellPrice", optionalApiNumber(currentApi, "getSellPrice", material, price));
        item.put("highPrice", number(invoke(snapshot, "highPrice")).doubleValue());
        item.put("lowPrice", number(invoke(snapshot, "lowPrice")).doubleValue());
        item.put("buyVolume", number(invoke(snapshot, "buyVolume")).doubleValue());
        item.put("sellVolume", number(invoke(snapshot, "sellVolume")).doubleValue());
        item.put("updatedAt", Instant.ofEpochMilli(number(invoke(snapshot, "updatedAt")).longValue()).toString());
        return item;
    }

    private static Map<String, Object> indexData(Object index) throws ReflectiveOperationException {
        Map<String, Object> value = new LinkedHashMap<>();
        double current = number(invoke(index, "current")).doubleValue();
        double previous = number(invoke(index, "previous")).doubleValue();
        value.put("current", current);
        value.put("previous", previous);
        value.put("changePercent", previous <= 0 ? 0 : (current / previous - 1) * 100);
        value.put("updatedAt", Instant.ofEpochMilli(number(invoke(index, "timestamp")).longValue()).toString());
        return value;
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (InvocationTargetException error) {
            throw reflected(error);
        }
    }

    private static String optionalString(Object target, String name, String fallback) {
        try {
            Object result = invoke(target, name);
            return result instanceof String value && !value.isBlank() ? value : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static String optionalApiString(Object target, String name, Object argument, String fallback) {
        try {
            Object result = apiMethod(target, name, argument).invoke(target, argument);
            return result instanceof String value && !value.isBlank() ? value : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static double optionalApiNumber(Object target, String name, Object argument, double fallback) {
        try {
            Object result = apiMethod(target, name, argument).invoke(target, argument);
            double value = number(result).doubleValue();
            return Double.isFinite(value) && value >= 0 ? value : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static Method apiMethod(Object target, String name, Object argument) throws NoSuchMethodException {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(argument)) return method;
        }
        throw new NoSuchMethodException(name);
    }

    private static Collection<?> collection(Object value) {
        if (value instanceof Collection<?> collection) return collection;
        throw new IllegalStateException("MonaPrice getItems did not return a collection");
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalStateException("MonaPrice returned a non-numeric value");
    }

    private static ReflectiveOperationException reflected(InvocationTargetException error) {
        Throwable cause = error.getCause();
        return new ReflectiveOperationException(cause == null ? error : cause);
    }

    private static Throwable root(Throwable error) {
        return error instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : error;
    }

    private static String pretty(String id) {
        StringBuilder result = new StringBuilder();
        for (String word : id.toLowerCase(java.util.Locale.ROOT).split("_")) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(listener);
        api = null;
        provider = null;
    }
}
