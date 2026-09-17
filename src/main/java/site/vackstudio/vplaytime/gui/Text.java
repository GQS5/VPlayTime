package site.vackstudio.vplaytime.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single internal text path: legacy Bukkit {@code &} codes and MiniMessage
 * share one representation.
 *
 * <p>Every configurable text (names, lore, titles, messages, button text)
 * is normalized ONCE at load: legacy codes compile to MiniMessage tags and
 * the result is trial-parsed, so a typo fails the reload with a human
 * message instead of breaking at click time. Runtime systems only ever see
 * validated MiniMessage and never reparse static configuration.
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Bare-word tags (no :args) the validator accepts; arg-form tags pass through. */
    private static final java.util.Set<String> KNOWN_TAGS = java.util.Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white",
            "bold", "b", "italic", "em", "i", "underlined", "underline", "u",
            "strikethrough", "strike", "s", "obfuscated", "obf",
            "reset", "newline", "br", "rainbow", "pride");

    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z_][A-Za-z_0-9]*)\\s*([^<>]*)>");
    private static final Pattern UNCLOSED = Pattern.compile("<[A-Za-z_/][^<>]*$");

    private static final Map<Character, String> COLORS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"));

    private static final Map<Character, String> FORMATS = Map.ofEntries(
            Map.entry('k', "obfuscated"), Map.entry('l', "bold"), Map.entry('m', "strikethrough"),
            Map.entry('n', "underline"), Map.entry('o', "italic"));

    private static final Pattern LEGACY = Pattern.compile("&(.)", Pattern.DOTALL);

    private Text() {
    }

    /** Compiles legacy {@code &} codes to MiniMessage tags ({@code &&} = literal &). */
    public static String legacyToMini(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        // Protect escaped ampersands before converting.
        String protected_ = text.replace("&&", "\0");
        Matcher matcher = LEGACY.matcher(protected_);
        StringBuilder out = new StringBuilder(protected_.length() + 16);
        while (matcher.find()) {
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            String replacement;
            if (code == 'r') {
                replacement = "<reset>";
            } else if (COLORS.containsKey(code)) {
                replacement = "<" + COLORS.get(code) + ">";
            } else if (FORMATS.containsKey(code)) {
                replacement = "<" + FORMATS.get(code) + ">";
            } else {
                replacement = matcher.group(0); // unknown sequence: leave literally
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString().replace("\0", "&");
    }

    /**
     * Load-time normalization for one configured text field: compiles legacy
     * codes, then validates the MiniMessage. Returns the runtime string.
     *
     * @throws IllegalStateException naming the field on invalid MiniMessage
     */
    public static String normalize(String field, String value) {
        String compiled = legacyToMini(value == null ? "" : value);
        if (UNCLOSED.matcher(compiled).find()) {
            throw new IllegalStateException("Invalid text in " + field + " '" + value
                    + "': tag opened with '<' but never closed with '>'.");
        }
        try {
            MINI.deserialize(compiled);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid text in " + field + " '" + value
                    + "': " + ex.getMessage());
        }
        checkTags(field, value, compiled);
        return compiled;
    }

    /**
     * Unknown bare-word tags are rejected with a named error; tags carrying
     * arguments ({@code <gradient:...>}, {@code <hover:...>},
     * {@code <font:...>}, ...) pass through to the runtime parser, which
     * already accepted the string above.
     */
    private static void checkTags(String field, String raw, String compiled) {
        Matcher matcher = TAG.matcher(compiled);
        while (matcher.find()) {
            if (!matcher.group(1).isEmpty()) {
                continue; // closing tag mirrors its (checked) opening tag
            }
            String name = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
            String rest = matcher.group(3);
            if (rest != null && rest.strip().startsWith(":")) {
                continue;
            }
            if (!KNOWN_TAGS.contains(name)) {
                throw new IllegalStateException("Invalid text in " + field + " '" + raw
                        + "': unknown formatting tag '<" + matcher.group(2) + ">'."
                        + " Check the tag name (MiniMessage tags need closing, e.g. <green>Hi</green>).");
            }
        }
    }
}
