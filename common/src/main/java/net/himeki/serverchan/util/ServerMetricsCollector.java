package net.himeki.serverchan.util;

import com.google.gson.JsonObject;
import net.himeki.serverchan.ServerChanCore;

import java.util.List;
import java.util.UUID;

/**
 * Builds the JSON payload returned by the get_server_metrics tool:
 * TPS (1m/5m/15m), online players, JVM memory usage and the caller's ping.
 * All platform data comes from {@link ServerInfoProvider}; JVM memory is universal.
 */
public final class ServerMetricsCollector {

    private ServerMetricsCollector() {}

    /** @return JSON string with the current server metrics. */
    public static String collect(UUID callerUuid, String callerName) {
        JsonObject root = new JsonObject();

        ServerInfoProvider info = ServerChanCore.getServerInfoProvider();
        boolean platformDataAvailable = info != null;
        root.addProperty("server_software", platformDataAvailable ? "platform provider" : "no platform provider registered");

        root.add("tps", buildTps(info));
        root.add("players", buildPlayers(info, callerUuid));
        root.add("worlds", buildWorlds(info));
        root.add("memory", buildMemory());
        root.addProperty("caller_ping_ms", callerPing(info, callerUuid));

        return root.toString();
    }

    private static com.google.gson.JsonArray buildWorlds(ServerInfoProvider info) {
        com.google.gson.JsonArray worlds = new com.google.gson.JsonArray();
        if (info == null) {
            return worlds;
        }
        for (ServerInfoProvider.WorldInfo world : info.getWorldsInfo()) {
            JsonObject w = new JsonObject();
            w.addProperty("name", world.name);
            w.addProperty("time_of_day", world.timeFormatted);
            w.addProperty("time_ticks", world.timeOfDayTicks);
            w.addProperty("day", world.day);
            w.addProperty("full_time_ticks", world.fullTime);
            w.addProperty("storming", world.storming);
            w.addProperty("thundering", world.thundering);
            worlds.add(w);
        }
        return worlds;
    }

    private static JsonObject buildTps(ServerInfoProvider info) {
        JsonObject tps = new JsonObject();
        double[] values = info != null ? info.getTps() : null;
        if (values != null && values.length >= 3) {
            tps.addProperty("1m", round2(values[0]));
            tps.addProperty("5m", round2(values[1]));
            tps.addProperty("15m", round2(values[2]));
        } else {
            tps.addProperty("error", "TPS is not available on this platform");
        }
        return tps;
    }

    private static JsonObject buildPlayers(ServerInfoProvider info, UUID callerUuid) {
        JsonObject players = new JsonObject();
        int online = info != null ? info.getCurrentOnline() : -1;
        int max = info != null ? info.getMaxPlayers() : -1;
        players.addProperty("online", online);
        players.addProperty("max", max);

        com.google.gson.JsonArray names = new com.google.gson.JsonArray();
        if (info != null) {
            List<String> playerNames = info.getPlayerNames();
            for (String name : playerNames) {
                names.add(name);
            }
        }
        players.add("names", names);
        return players;
    }

    private static JsonObject buildMemory() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long used = total - free;

        JsonObject memory = new JsonObject();
        memory.addProperty("free_mb", toMb(free));
        memory.addProperty("used_mb", toMb(used));
        memory.addProperty("total_mb", toMb(total));
        memory.addProperty("max_mb", toMb(max));
        memory.addProperty("usage_percent", max > 0 ? round2(used * 100.0 / max) : -1);
        return memory;
    }

    private static int callerPing(ServerInfoProvider info, UUID callerUuid) {
        return (info != null && callerUuid != null) ? info.getPlayerPing(callerUuid) : -1;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static long toMb(long bytes) {
        return bytes / (1024L * 1024L);
    }
}
