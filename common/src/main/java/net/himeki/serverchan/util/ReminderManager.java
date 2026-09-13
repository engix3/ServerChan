package net.himeki.serverchan.util;

import net.himeki.serverchan.ServerChanCore;
import net.himeki.serverchan.i18n.I18n;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Player reminders scheduled by the AI via the set_reminder tool.
 *
 * Runs on a platform-independent daemon scheduler and delivers the reminder
 * through the MessageBroadcaster. Reminders are in-memory: they fire while the
 * server is running and are dropped on restart.
 */
public final class ReminderManager {

    private static final int MAX_PENDING_PER_PLAYER = 10;
    private static final int MAX_DELAY_MINUTES = 1440; // 24 hours

    private static ScheduledExecutorService executor;
    private static final Map<UUID, Integer> pending = new ConcurrentHashMap<>();

    private ReminderManager() {}

    /**
     * Schedule a reminder for a player.
     *
     * @return a confirmation or error message for the model.
     */
    public static synchronized String schedule(UUID playerUuid, String playerName, int minutes, String text) {
        if (minutes < 1 || minutes > MAX_DELAY_MINUTES) {
            return "Error: delay must be between 1 and " + MAX_DELAY_MINUTES + " minutes";
        }
        if (text == null || text.trim().isEmpty()) {
            return "Error: reminder text is empty";
        }
        int pendingCount = pending.getOrDefault(playerUuid, 0);
        if (pendingCount >= MAX_PENDING_PER_PLAYER) {
            return "Error: this player already has " + pendingCount
                    + " pending reminders (max " + MAX_PENDING_PER_PLAYER + ")";
        }

        ScheduledExecutorService service = executor;
        if (service == null) {
            service = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "ServerChan-Reminders");
                thread.setDaemon(true);
                return thread;
            });
            executor = service;
        }

        final String safeName = playerName;
        final String safeText = text.trim();
        pending.merge(playerUuid, 1, Integer::sum);
        service.schedule(() -> fire(playerUuid, safeName, safeText), minutes * 60L, TimeUnit.SECONDS);

        return "Reminder set: will fire in " + minutes + " minute(s) - '" + safeText + "'";
    }

    private static void fire(UUID playerUuid, String playerName, String text) {
        pending.computeIfPresent(playerUuid, (key, count) -> count <= 1 ? null : count - 1);

        if (!ServerChanCore.isEnabled()) {
            return;
        }
        net.himeki.serverchan.MessageBroadcaster broadcaster = ServerChanCore.getMessageBroadcaster();
        if (broadcaster == null || !broadcaster.isReady()) {
            return;
        }
        broadcaster.broadcastMessage(ServerChanCore.formatForChat(I18n.format("reminder.fire", playerName, text)));
    }

    /** Shut the reminder scheduler down (call on plugin shutdown). */
    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        pending.clear();
    }
}
