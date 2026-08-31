package jp.monakaserver.monakabu.web.bedrock;

import java.time.Instant;
import java.time.ZoneId;
import org.bukkit.entity.Player;

/** Optional bridge for displaying a one-time code with a native Bedrock form. */
public interface BedrockLinkPresenter {
    boolean present(Player player, String siteUrl, String code, Instant expiresAt, ZoneId timezone);
}
