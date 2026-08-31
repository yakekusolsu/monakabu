package jp.monakaserver.monakabu.web.bedrock;

import java.time.Instant;
import java.time.ZoneId;
import org.bukkit.entity.Player;
import org.geysermc.geyser.api.GeyserApi;

/** Loaded reflectively only when the Geyser API is available. */
public final class GeyserLinkPresenter implements BedrockLinkPresenter {
    public GeyserLinkPresenter() {}

    @Override
    public boolean present(Player player, String siteUrl, String code, Instant expiresAt, ZoneId timezone) {
        GeyserApi api = GeyserApi.api();
        return api != null && api.isBedrockPlayer(player.getUniqueId())
                && api.sendForm(player.getUniqueId(), BedrockLinkForm.create(siteUrl, code, expiresAt, timezone));
    }
}
