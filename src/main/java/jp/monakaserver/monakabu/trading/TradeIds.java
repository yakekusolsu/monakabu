package jp.monakaserver.monakabu.trading;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TradeIds {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private TradeIds() {}

    public static String random(String prefix, ZoneId zone) {
        StringBuilder suffix = new StringBuilder(10);
        for (int i = 0; i < 10; i++) suffix.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return prefix + "-" + LocalDate.now(zone).format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + suffix;
    }

    public static String settlement(int season, String uuid, String stockId) {
        return "SETTLE-S" + season + "-" + uuid.replace("-", "") + "-" + stockId;
    }
}
