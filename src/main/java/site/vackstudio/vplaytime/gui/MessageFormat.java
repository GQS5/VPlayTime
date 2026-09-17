package site.vackstudio.vplaytime.gui;

import java.util.Map;

/**
 * Chat-message placeholder substitution for {@code messages.yml} templates.
 *
 * <p>Single pass over the template: {@code %key%} tokens with a value are
 * replaced (MiniMessage-escaped, so names/ids/details can never inject
 * formatting or break parsing); tokens without a value are dropped, so a
 * missing value never throws and never leaks raw tokens into chat. Lone
 * percents ("100%") pass through untouched, and percent signs inside
 * substituted VALUES are kept verbatim (e.g. a reason quoting
 * {@code %statistic_seconds_played%}).
 *
 * <p>Separate from {@link Placeholders} (GUI lore, which leaves unknown
 * tokens untouched): chat messages must never show raw {@code %…%}.
 */
public final class MessageFormat {

    private MessageFormat() {
    }

    public static String format(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return template == null ? "" : template;
        }
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '%' && i + 1 < template.length()) {
                int end = template.indexOf('%', i + 1);
                if (end > i + 1 && isToken(template.substring(i + 1, end))) {
                    String key = template.substring(i + 1, end);
                    String value = values == null ? null : values.get(key);
                    if (value != null) {
                        out.append(escape(value));
                    }
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** MiniMessage-escape for substituted values (mirrors command escaping). */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("<", "\\<");
    }

    private static boolean isToken(String inner) {
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (!(c == '_' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }
}
