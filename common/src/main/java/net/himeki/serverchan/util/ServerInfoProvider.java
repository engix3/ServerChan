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

    /** Snapshot of a single world's time and weather. */
    class WorldInfo {
        public final String name;
        public final long timeOfDayTicks;
        public final String timeFormatted;
        public final long fullTime;
        public final long day;
        public final boolean storming;
        public final boolean thundering;

        public WorldInfo(String name, long timeOfDayTicks, String timeFormatted,
                         long fullTime, long day, boolean storming, boolean thundering) {
            this.name = name;
            this.timeOfDayTicks = timeOfDayTicks;
            this.timeFormatted = timeFormatted;
            this.fullTime = fullTime;
            this.day = day;
            this.storming = storming;
            this.thundering = thundering;
        }
    }

    /**
     * @return TPS averages [1m, 5m, 15m], or null if the platform cannot provide them.
     */
    default double[] getTps() {
        return null;
    }

    /**
     * @return info about every loaded world (time, day, weather), or an empty list.
     */
    default List<WorldInfo> getWorldsInfo() {
        return Collections.emptyList();
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
