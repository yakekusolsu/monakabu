package jp.monakaserver.monakabu.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern PART = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);

    private DurationParser() {}

    public static Duration parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Duration is blank");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("pt")) return Duration.parse(normalized.toUpperCase(Locale.ROOT));
        Matcher matcher = PART.matcher(normalized);
        long seconds = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) throw new IllegalArgumentException("Invalid duration: " + value);
            long amount = Long.parseLong(matcher.group(1));
            seconds = Math.addExact(seconds, switch (matcher.group(2)) {
                case "s" -> amount;
                case "m" -> Math.multiplyExact(amount, 60);
                case "h" -> Math.multiplyExact(amount, 3_600);
                case "d" -> Math.multiplyExact(amount, 86_400);
                case "w" -> Math.multiplyExact(amount, 604_800);
                default -> throw new IllegalArgumentException("Invalid duration: " + value);
            });
            end = matcher.end();
        }
        if (end != normalized.length() || seconds <= 0) throw new IllegalArgumentException("Invalid duration: " + value);
        return Duration.ofSeconds(seconds);
    }

    public static String formatJapanese(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        if (days > 0) return days + "日" + hours + "時間";
        if (hours > 0) return hours + "時間" + minutes + "分";
        if (minutes > 0) return minutes + "分";
        return seconds + "秒";
    }
}
