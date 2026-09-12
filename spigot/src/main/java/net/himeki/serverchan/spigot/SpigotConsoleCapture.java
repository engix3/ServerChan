package net.himeki.serverchan.spigot;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * An in-memory Log4j2 appender that captures console log lines.
 *
 * Vanilla (Brigadier) command feedback on Paper is written to the server console
 * through the server's own CommandSourceStack, NOT through the Bukkit CommandSender
 * that dispatched the command - so a buffered CommandSender never sees it. Attaching
 * this appender to the root logger for the duration of a synchronous dispatch captures
 * exactly the lines the command printed, no matter which path they took.
 */
final class SpigotConsoleCapture extends AbstractAppender {

    private final StringBuffer buffer;

    private SpigotConsoleCapture(StringBuffer buffer) {
        super("ServerChanCapture", null, null, true, Property.EMPTY_ARRAY);
        this.buffer = buffer;
    }

    static SpigotConsoleCapture attach(StringBuffer buffer) {
        SpigotConsoleCapture capture = new SpigotConsoleCapture(buffer);
        capture.start();
        org.apache.logging.log4j.core.Logger rootLogger =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
        rootLogger.addAppender(capture);
        return capture;
    }

    void detach() {
        org.apache.logging.log4j.core.Logger rootLogger =
                (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getRootLogger();
        rootLogger.removeAppender(this);
        stop();
    }

    @Override
    public void append(LogEvent event) {
        // Command dispatches run synchronously on the server thread; ignore noise
        // logged by other threads during the capture window
        String thread = event.getThreadName();
        if (thread != null && !thread.equals("Server thread") && !thread.equals("main")) {
            return;
        }
        String message = event.getMessage() != null ? event.getMessage().getFormattedMessage() : null;
        if (message != null && !message.isEmpty()) {
            synchronized (buffer) {
                buffer.append(message).append('\n');
            }
        }
    }
}
