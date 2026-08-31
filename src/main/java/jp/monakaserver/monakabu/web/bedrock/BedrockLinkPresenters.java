package jp.monakaserver.monakabu.web.bedrock;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads optional Geyser/Floodgate adapters without making either plugin mandatory. */
public final class BedrockLinkPresenters {
    private static final String FLOODGATE_API = "org.geysermc.floodgate.api.FloodgateApi";
    private static final String GEYSER_API = "org.geysermc.geyser.api.GeyserApi";
    private static final String FLOODGATE_ADAPTER =
            "jp.monakaserver.monakabu.web.bedrock.FloodgateLinkPresenter";
    private static final String GEYSER_ADAPTER =
            "jp.monakaserver.monakabu.web.bedrock.GeyserLinkPresenter";

    private BedrockLinkPresenters() {}

    public static BedrockLinkPresenter detect(JavaPlugin plugin) {
        ArrayList<NamedPresenter> presenters = new ArrayList<>();
        load(plugin, FLOODGATE_API, FLOODGATE_ADAPTER, "Floodgate", presenters);
        load(plugin, GEYSER_API, GEYSER_ADAPTER, "Geyser", presenters);
        if (presenters.isEmpty()) {
            plugin.getLogger().info("Bedrock link forms disabled (Geyser/Floodgate API not found)");
            return (player, siteUrl, code, expiresAt, timezone) -> false;
        }
        plugin.getLogger().info("Bedrock link forms enabled via "
                + String.join(", ", presenters.stream().map(NamedPresenter::name).toList()));
        List<NamedPresenter> loaded = List.copyOf(presenters);
        return (player, siteUrl, code, expiresAt, timezone) -> present(
                plugin, loaded, player, siteUrl, code, expiresAt, timezone);
    }

    private static void load(JavaPlugin plugin, String apiClass, String adapterClass, String name,
                             List<NamedPresenter> destination) {
        ClassLoader loader = plugin.getClass().getClassLoader();
        try {
            Class.forName(apiClass, false, loader);
            Object adapter = Class.forName(adapterClass, true, loader).getConstructor().newInstance();
            destination.add(new NamedPresenter(name, (BedrockLinkPresenter) adapter));
        } catch (ClassNotFoundException ignored) {
            // Optional integration is not installed on this Paper server.
        } catch (InvocationTargetException error) {
            plugin.getLogger().log(Level.WARNING, name + " link form adapter failed to initialize",
                    error.getCause());
        } catch (ReflectiveOperationException | LinkageError error) {
            plugin.getLogger().log(Level.WARNING, name + " link form adapter is incompatible", error);
        }
    }

    private static boolean present(JavaPlugin plugin, List<NamedPresenter> presenters, Player player,
                                   String siteUrl, String code, java.time.Instant expiresAt,
                                   java.time.ZoneId timezone) {
        for (NamedPresenter presenter : presenters) {
            try {
                if (presenter.presenter().present(player, siteUrl, code, expiresAt, timezone)) return true;
            } catch (RuntimeException | LinkageError error) {
                plugin.getLogger().log(Level.WARNING,
                        presenter.name() + " could not show a link form for " + player.getUniqueId(), error);
            }
        }
        return false;
    }

    private record NamedPresenter(String name, BedrockLinkPresenter presenter) {}
}
