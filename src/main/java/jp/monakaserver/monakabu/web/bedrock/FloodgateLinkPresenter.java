package jp.monakaserver.monakabu.web.bedrock;

import java.time.Instant;
import java.time.ZoneId;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

/** Loaded reflectively only when the Floodgate API is available. */
public final class FloodgateLinkPresenter implements BedrockLinkPresenter {
    public FloodgateLinkPresenter() {}

    @Override
    public boolean present(Player player, String siteUrl, String code, Instant expiresAt, ZoneId timezone) {
        FloodgateApi api = FloodgateApi.getInstance();
        return api.isFloodgatePlayer(player.getUniqueId())
                && api.sendForm(player.getUniqueId(), BedrockLinkForm.create(siteUrl, code, expiresAt, timezone));
    }
}
