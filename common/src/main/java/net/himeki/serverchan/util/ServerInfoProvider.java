package net.himeki.serverchan.util;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    /** Snapshot of one online player's live state. */
    class PlayerInfo {
        public final String name;
        public final UUID uuid;
        public final String world;
        public final double x;
        public final double y;
        public final double z;
        public final double health;
        public final int hunger;
        public final String gamemode;
        public final int pingMs;
        public final double playtimeHours;

        public PlayerInfo(String name, UUID uuid, String world, double x, double y, double z,
                          double health, int hunger, String gamemode, int pingMs, double playtimeHours) {
            this.name = name;
            this.uuid = uuid;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.health = health;
            this.hunger = hunger;
            this.gamemode = gamemode;
            this.pingMs = pingMs;
            this.playtimeHours = playtimeHours;
        }
    }

    /**
     * @return TPS averages [1m, 5m, 15m], or null if the platform cannot provide them.
     */
    default double[] getTps() {
        return null;
    }

    /**
     * @return live info about an online player by exact name, or null if unknown/offline.
     */
    default PlayerInfo getPlayerInfo(String playerName) {
        return null;
    }

    /**
     * @return current ping (ms) of every online player, keyed by name.
     */
    default Map<String, Integer> getPlayerPingsByName() {
        return Collections.emptyMap();
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
