package net.himeki.serverchan.util;

import net.himeki.serverchan.ServerChanCore;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chat formatting utilities: legacy color code translation (& -> §)
 * and bot prefix handling (configurable, no hardcoded prefixes, no duplicates).
 */
public final class ChatFormat {

    /** Standard legacy color/format codes that players and admins may write as &x. */
    private static final Pattern AMP_CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    /** Full hex colors as written by many plugins: &#RRGGBB -> native §x§R§R§G§G§B§B. */
    private static final Pattern HEX_COLOR = Pattern.compile("&#([0-9a-fA-F]{6})");

    /** Broken hex attempts like '&#c' or '&#ff' - drop the '#' so the legacy code still applies. */
    private static final Pattern BROKEN_HEX_HASH = Pattern.compile("&#(?=[0-9a-fA-F])");

    /** Strips § sequences for prefix-duplicate detection. */
    private static final Pattern SECTION_CODES = Pattern.compile("§[0-9a-fk-orA-FK-ORxX]");

    private ChatFormat() {}

    /**
     * Translates legacy '&' formatting codes into native '§' codes.
     * Also understands full hex colors (&#RRGGBB) and repairs broken hex attempts
     * like '&#c' (the model sometimes writes plugin-style hex instead of &c).
     * Unknown '&x' sequences are left untouched so player chat is not mangled.
     */
    public static String translateAmpCodes(String input) {
        if (input == null || input.indexOf('&') < 0) {
            return input;
        }
        String result = translateHexColors(input);
        result = BROKEN_HEX_HASH.matcher(result).replaceAll("&");
        return AMP_CODE.matcher(result).replaceAll("§$1");
    }

    /** Java 8 compatible &#RRGGBB -> §x§R§R§G§G§B§B translation. */
    private static String translateHexColors(String input) {
        java.util.regex.Matcher matcher = HEX_COLOR.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuffer result = new StringBuffer();
        do {
            StringBuilder code = new StringBuilder("§x");
            for (char ch : matcher.group(1).toLowerCase(Locale.ROOT).toCharArray()) {
                code.append('§').append(ch);
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(code.toString()));
        } while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }

    /** Removes all legacy § formatting codes from a string. */
    public static String stripColorCodes(String input) {
        if (input == null) {
            return null;
        }
        return SECTION_CODES.matcher(input).replaceAll("");
    }

    /**
     * Returns the configured bot prefix (e.g. "§d§l[Нейрона]§r "),
     * with '&' codes already translated to '§'.
     */
    public static String getBotPrefix() {
        String prefix = ServerChanCore.CONFIG != null ? ServerChanCore.CONFIG.botPrefix : null;
        if (prefix == null) {
            prefix = "§d§l[Нейрона]§r ";
        }
        return translateAmpCodes(prefix);
    }

    /**
     * Full chat formatting pipeline for messages the bot broadcasts:
     * 1. translates '&' codes to '§';
     * 2. prepends the configurable bot prefix unless the message already starts with it
     *    (protects against duplicate prefixes when the model echoes one).
     */
    public static String formatBotMessage(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String formatted = translateAmpCodes(message);
        String prefix = getBotPrefix();
        if (prefix.isEmpty()) {
            return formatted;
        }
        // Compare plain text (formatting codes removed) so "&d&l[Нейрона]" still counts as a duplicate
        String plainMessage = stripColorCodes(formatted).trim();
        String plainPrefix = stripColorCodes(prefix).trim();
        if (plainMessage.startsWith(plainPrefix)) {
            return formatted;
        }
        return prefix + formatted;
    }
}
