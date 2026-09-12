package net.himeki.serverchan.util;

import net.himeki.serverchan.ServerChanCore;

import java.util.regex.Pattern;

/**
 * Chat formatting utilities: legacy color code translation (& -> §)
 * and bot prefix handling (configurable, no hardcoded prefixes, no duplicates).
 */
public final class ChatFormat {

    /** Standard legacy color/format codes that players and admins may write as &x. */
    private static final Pattern AMP_CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    /** Strips § sequences for prefix-duplicate detection. */
    private static final Pattern SECTION_CODES = Pattern.compile("§[0-9a-fk-orA-FK-ORxX]");

    private ChatFormat() {}

    /**
     * Translates legacy '&' formatting codes into native '§' codes.
     * Unknown '&x' sequences are left untouched so player chat is not mangled.
     */
    public static String translateAmpCodes(String input) {
        if (input == null || input.indexOf('&') < 0) {
            return input;
        }
        return AMP_CODE.matcher(input).replaceAll("§$1");
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
