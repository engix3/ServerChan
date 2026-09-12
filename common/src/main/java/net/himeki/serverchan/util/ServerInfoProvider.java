package net.himeki.serverchan.util;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Platform-specific server information provider used by the get_server_metrics tool.
 * All methods have safe defaults so platforms that don't implement a provider
 * degrade gracefully instead of failing.
 */
public interface ServerInfoProvider {

    /**
     * @return TPS averages [1m, 5m, 15m], or null if the platform cannot provide them.
     */
    default double[] getTps() {
        return null;
    }

    /**
     * @return current online player count, or -1 if unknown.
     */
    default int getCurrentOnline() {
        return -1;
    }

    /**
     * @return maximum player count, or -1 if unknown.
     */
    default int getMaxPlayers() {
        return -1;
    }

    /**
     * @return names of all online players (never null).
     */
    default List<String> getPlayerNames() {
        return Collections.emptyList();
    }

    /**
     * @return ping of the given player in ms, or -1 if unknown.
     */
    default int getPlayerPing(UUID playerUuid) {
        return -1;
    }
}
