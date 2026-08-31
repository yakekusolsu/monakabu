package jp.monakaserver.monakabu.web.bedrock;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.geysermc.cumulus.form.CustomForm;

final class BedrockLinkForm {
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private BedrockLinkForm() {}

    static CustomForm.Builder create(String siteUrl, String code, Instant expiresAt, ZoneId timezone) {
        String expiry = EXPIRY.withZone(timezone).format(expiresAt);
        return CustomForm.builder()
                .title("MonaKabu サイト連携")
                .label("次のワンタイムコードをWebサイトで入力してください。\n"
                        + "サイト: " + siteUrl + "\n有効期限: " + expiry)
                .input("連携コード（長押しでコピー）", code, code)
                .label("コードは1回限りです。他人には見せないでください。\n"
                        + "フォームを閉じても、コードはチャットで確認できます。");
    }
}
