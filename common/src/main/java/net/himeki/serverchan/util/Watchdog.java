package net.himeki.serverchan.util;

import net.himeki.serverchan.ServerChanCore;
import net.himeki.serverchan.config.ServerChanConfigBase;
import net.himeki.serverchan.i18n.I18n;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic health monitoring: warns in chat when TPS drops or player pings spike
 * (possible DDoS / network trouble). Deterministic messages - no AI calls involved.
 *
 * Config values are re-read on every cycle, so /serverchan reload applies them live.
 */
public final class Watchdog {

    private static final int TICK_SECONDS = 5;

    private static ScheduledExecutorService executor;
    private static volatile long lastCheck = 0;
    private static volatile boolean tpsAlerted = false;
    private static volatile boolean pingAlerted = false;
    private static volatile long lastTpsAlert = 0;
    private static volatile long lastPingAlert = 0;

    private Watchdog() {}

    /** Start the watchdog (safe to call multiple times). */
    public static synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ServerChan-Watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(Watchdog::tick, 60, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** Stop the watchdog (call on plugin shutdown). */
    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void tick() {
        try {
            ServerChanConfigBase config = ServerChanCore.CONFIG;
            if (config == null || !config.watchdogEnabled) {
                return;
            }
            ServerInfoProvider info = ServerChanCore.getServerInfoProvider();
            if (info == null) {
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastCheck < config.watchdogCheckIntervalSeconds * 1000L) {
                return;
            }
            lastCheck = now;

            long cooldownMs = config.watchdogCooldownSeconds * 1000L;

            StringBuilder recovered = new StringBuilder();

            // --- TPS ---
            double[] tps = info.getTps();
            if (tps != null && tps.length >= 1 && tps[0] > 0) {
                if (tps[0] < config.watchdogTpsThreshold) {
                    if (!tpsAlerted || now - lastTpsAlert >= cooldownMs) {
                        tpsAlerted = true;
                        lastTpsAlert = now;
                        broadcast(I18n.format("watchdog.tps",
                                String.format(Locale.ROOT, "%.2f", tps[0]),
                                String.valueOf(config.watchdogTpsThreshold)));
                    }
                } else if (tpsAlerted) {
                    tpsAlerted = false;
                    recovered.append("TPS ").append(String.format(Locale.ROOT, "%.2f", tps[0]));
                }
            }

            // --- Player pings ---
            Map<String, Integer> pings = info.getPlayerPingsByName();
            if (!pings.isEmpty()) {
                int maxPing = -1;
                String worstPlayer = null;
                for (Map.Entry<String, Integer> entry : pings.entrySet()) {
                    int ping = entry.getValue();
                    if (ping > maxPing) {
                        maxPing = ping;
                        worstPlayer = entry.getKey();
                    }
                }

                if (maxPing > config.watchdogPingThresholdMs) {
                    if (!pingAlerted || now - lastPingAlert >= cooldownMs) {
                        pingAlerted = true;
                        lastPingAlert = now;
                        broadcast(I18n.format("watchdog.ping",
                                String.valueOf(maxPing), String.valueOf(worstPlayer)));
                    }
                } else if (pingAlerted) {
                    pingAlerted = false;
                    if (recovered.length() > 0) {
                        recovered.append(", ");
                    }
                    recovered.append("ping");
                }
            }

            if (recovered.length() > 0) {
                broadcast(I18n.get("watchdog.recovered"));
            }
        } catch (Throwable t) {
            ServerChanCore.LOGGER.debug("Watchdog cycle failed", t);
        }
    }

    private static void broadcast(String message) {
        if (!ServerChanCore.isEnabled()) {
            return;
        }
        net.himeki.serverchan.MessageBroadcaster broadcaster = ServerChanCore.getMessageBroadcaster();
        if (broadcaster != null && broadcaster.isReady()) {
            broadcaster.broadcastMessage(ServerChanCore.formatForChat(message));
        }
    }
}
