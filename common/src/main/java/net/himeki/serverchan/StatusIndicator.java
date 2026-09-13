package net.himeki.serverchan;

/**
 * Platform-specific status indicator (e.g. an action bar above the hotbar)
 * that shows what the AI is currently doing. Text updates in place and is
 * visible to every online player - chat stays clean for final answers.
 */
public interface StatusIndicator {

    /**
     * Show/update the status text.
     * @param text formatted text (legacy § codes allowed)
     */
    void show(String text);

    /** Hide the status (e.g. when the final answer is about to be sent). */
    void clear();

    /** @return true when the indicator can currently display text. */
    boolean isReady();
}
