package net.himeki.serverchan.spigot;

import net.himeki.serverchan.ServerChanCore;
import net.himeki.serverchan.StatusIndicator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;

/**
 * Status indicator for Spigot/Paper: shows what the AI is doing in the action
 * bar (the line above the hotbar) of every online player. The text updates in
 * place while the AI thinks or runs tools, and is cleared once the answer is
 * broadcast - chat stays clean.
 *
 * Action bar sending goes through reflection. Modern Paper builds expose
 * Player#sendActionBar(Adventure Component); older ones use the legacy
 * Player.Spigot#sendMessage(ChatMessageType, BaseComponent...) path. Both are
 * tried; if neither exists the indicator logs a warning and becomes a no-op.
 */
public class SpigotStatusIndicator implements StatusIndicator {

    private static final long REFRESH_TICKS = 40L; // action bar fades after ~2-3s

    private final JavaPlugin plugin;
    private volatile String currentText;
    private BukkitTask refreshTask;

    // Lazily resolved reflection handles
    private static volatile boolean reflectionResolved = false;
    private static Method adventureSendActionBar;
    private static Object adventureSerializer;
    private static Method adventureDeserialize;
    private static Method spigotSendMessage;
    private static Object actionBarMessageType;
    private static Method fromLegacyMethod;

    public SpigotStatusIndicator(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isReady() {
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public void show(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        currentText = text;
        ensureRefreshTask();
        runOnMain(() -> sendToAll(currentText));
    }

    @Override
    public void clear() {
        currentText = null;
        stopRefreshTask();
        runOnMain(() -> sendToAll(""));
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void ensureRefreshTask() {
        if (refreshTask != null) {
            return;
        }
        synchronized (this) {
            if (refreshTask != null) {
                return;
            }
            refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                String text = currentText;
                if (text == null) {
                    stopRefreshTask();
                    return;
                }
                sendToAll(text);
            }, REFRESH_TICKS, REFRESH_TICKS);
        }
    }

    private synchronized void stopRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private void sendToAll(String text) {
        if (!plugin.isEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendActionBar(player, text);
        }
    }

    private void sendActionBar(Player player, String text) {
        try {
            if (!reflectionResolved) {
                resolveReflection(player);
            }
            if (adventureSendActionBar != null) {
                Object component = adventureDeserialize.invoke(adventureSerializer, text);
                adventureSendActionBar.invoke(player, component);
            } else if (spigotSendMessage != null) {
                Object components = fromLegacyMethod.invoke(null, text);
                spigotSendMessage.invoke(player.spigot(), actionBarMessageType, components);
            }
        } catch (Throwable t) {
            ServerChanCore.LOGGER.debug("Status indicator send failed", t);
        }
    }

    private static synchronized void resolveReflection(Player samplePlayer) {
        if (reflectionResolved) {
            return;
        }

        // Preferred: Paper's native Adventure API (Player#sendActionBar(Component))
        try {
            Class<?> serializerClass = Class.forName(
                    "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            // legacySection() is a static METHOD (not a field) in Adventure 4.x
            adventureSerializer = serializerClass.getMethod("legacySection").invoke(null);
            adventureDeserialize = serializerClass.getMethod("deserialize", String.class);
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            adventureSendActionBar = samplePlayer.getClass().getMethod("sendActionBar", componentClass);
        } catch (Throwable t) {
            ServerChanCore.LOGGER.debug("Adventure action bar unavailable: {}", t.toString());
        }

        // Fallback: legacy Spigot path (Player.Spigot#sendMessage(ChatMessageType, BaseComponent...))
        if (adventureSendActionBar == null) {
            try {
                Class<?> chatMessageType = Class.forName("net.md_5.bungee.api.chat.ChatMessageType");
                actionBarMessageType = chatMessageType.getField("ACTION_BAR").get(null);
                Class<?> baseComponentClass = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
                fromLegacyMethod = Class.forName("net.md_5.bungee.api.chat.TextComponent")
                        .getMethod("fromLegacyText", String.class);
                Object spigot = samplePlayer.spigot();
                for (Method method : spigot.getClass().getMethods()) {
                    if (method.getName().equals("sendMessage")
                            && method.getParameterCount() == 2
                            && method.getParameterTypes()[0] == chatMessageType
                            && method.getParameterTypes()[1].isArray()
                            && method.getParameterTypes()[1].getComponentType() == baseComponentClass) {
                        spigotSendMessage = method;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                // Legacy path not available either
            }
        }

        reflectionResolved = true;
        if (adventureSendActionBar != null) {
            ServerChanCore.LOGGER.info("Status indicator: using Adventure sendActionBar");
        } else if (spigotSendMessage != null) {
            ServerChanCore.LOGGER.info("Status indicator: using legacy Spigot action bar");
        } else {
            ServerChanCore.LOGGER.warn("Status indicator unavailable: no action bar method found on this server build");
        }
    }
}
