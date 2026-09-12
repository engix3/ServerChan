package net.himeki.serverchan.spigot;

import net.himeki.serverchan.util.ServerInfoProvider;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bukkit/Paper implementation of ServerInfoProvider.
 *
 * TPS (Paper's Server#getTPS) and player ping (Paper's Player#getPing) are not part
 * of the Spigot API this module compiles against, so they are accessed via reflection.
 * On Paper/Purpur servers they are always available; on plain Spigot the tool
 * reports them as unavailable instead of crashing.
 */
public class SpigotServerInfoProvider implements ServerInfoProvider {

    private volatile Method tpsMethod;
    private volatile boolean tpsMethodResolved = false;
    private volatile Method pingMethod;
    private volatile boolean pingMethodResolved = false;

    @Override
    public double[] getTps() {
        try {
            Server server = Bukkit.getServer();
            Method method = tpsMethod;
            if (!tpsMethodResolved) {
                method = server.getClass().getMethod("getTPS");
                tpsMethod = method;
                tpsMethodResolved = true;
            }
            double[] tps = (double[]) method.invoke(server);
            if (tps != null && tps.length >= 3) {
                // Only the 1m/5m/15m averages are meaningful for the model
                return new double[]{tps[0], tps[1], tps[2]};
            }
        } catch (Throwable ignored) {
            // Plain Spigot - TPS API not available
        }
        return null;
    }

    @Override
    public int getCurrentOnline() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public List<net.himeki.serverchan.util.ServerInfoProvider.WorldInfo> getWorldsInfo() {
        List<net.himeki.serverchan.util.ServerInfoProvider.WorldInfo> worlds = new ArrayList<>();
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            long timeOfDay = world.getTime();
            long fullTime = world.getFullTime();
            // Minecraft day starts at 06:00 (tick 0); 20 minutes real time per 24000 ticks
            int hours = (int) ((timeOfDay / 1000L + 6L) % 24L);
            int minutes = (int) ((timeOfDay % 1000L) * 60L / 1000L);
            worlds.add(new net.himeki.serverchan.util.ServerInfoProvider.WorldInfo(
                    world.getName(),
                    timeOfDay,
                    String.format("%02d:%02d", hours, minutes),
                    fullTime,
                    fullTime / 24000L,
                    world.hasStorm(),
                    world.isThundering()
            ));
        }
        return worlds;
    }

    @Override
    public int getMaxPlayers() {
        return Bukkit.getMaxPlayers();
    }

    @Override
    public List<String> getPlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    @Override
    public int getPlayerPing(UUID playerUuid) {
        if (playerUuid == null) {
            return -1;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return -1;
        }
        try {
            Method method = pingMethod;
            if (!pingMethodResolved) {
                method = player.getClass().getMethod("getPing");
                pingMethod = method;
                pingMethodResolved = true;
            }
            Object ping = method.invoke(player);
            if (ping instanceof Number) {
                return ((Number) ping).intValue();
            }
        } catch (Throwable ignored) {
            // Ping API not available on this platform
        }
        return -1;
    }
}
