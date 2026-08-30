package jp.monakaserver.monakabu.realtime;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

public final class JsonEncoder {
    private JsonEncoder() {}

    public static String encode(Object value) {
        StringBuilder result = new StringBuilder(512);
        append(result, value);
        return result.toString();
    }

    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("null");
        } else if (value instanceof String text) {
            string(target, text);
        } else if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof BigDecimal) {
            target.append(value);
        } else if (value instanceof Float number) {
            target.append(Float.isFinite(number) ? number : 0);
        } else if (value instanceof Double number) {
            target.append(Double.isFinite(number) ? number : 0);
        } else if (value instanceof Enum<?> enumeration) {
            string(target, enumeration.name());
        } else if (value instanceof Map<?, ?> map) {
            target.append('{');
            Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                string(target, String.valueOf(entry.getKey()));
                target.append(':');
                append(target, entry.getValue());
                if (iterator.hasNext()) target.append(',');
            }
            target.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            target.append('[');
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                append(target, iterator.next());
                if (iterator.hasNext()) target.append(',');
            }
            target.append(']');
        } else if (value.getClass().isArray()) {
            target.append('[');
            for (int index = 0; index < Array.getLength(value); index++) {
                if (index > 0) target.append(',');
                append(target, Array.get(value, index));
            }
            target.append(']');
        } else {
            string(target, String.valueOf(value));
        }
    }

    private static void string(StringBuilder target, String value) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (character < 0x20) target.append(String.format("\\u%04x", (int) character));
                    else target.append(character);
                }
            }
        }
        target.append('"');
    }
}
