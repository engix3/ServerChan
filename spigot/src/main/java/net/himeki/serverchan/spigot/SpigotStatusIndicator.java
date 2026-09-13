package net.himeki.serverchan.spigot;

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
 * Action bar sending goes through reflection: the Bukkit call
 * (Player.Spigot#sendMessage(ChatMessageType, BaseComponent...)) needs
 * bungeecord-chat classes that are intentionally not on the compile classpath.
 * On servers without the reflection targets the indicator degrades to a no-op.
 */
public class SpigotStatusIndicator implements StatusIndicator {

    private static final long REFRESH_TICKS = 40L; // action bar fades after ~2-3s

    private final JavaPlugin plugin;
    private volatile String currentText;
    private BukkitTask refreshTask;

    // Lazily resolved reflection handles
    private static volatile boolean reflectionResolved = false;
    private static Object actionBarMessageType;
    private static Class<?> baseComponentClass;
    private static Method fromLegacyMethod;
    private static Method spigotSendMessage;

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
            if (spigotSendMessage == null) {
                return;
            }
            Object components = fromLegacyMethod.invoke(null, text);
            spigotSendMessage.invoke(player.spigot(), actionBarMessageType, components);
        } catch (Throwable ignored) {
            // No action bar on this platform - status silently unavailable
        }
    }

    private static synchronized void resolveReflection(Player samplePlayer) throws Exception {
        if (reflectionResolved) {
            return;
        }
        Class<?> chatMessageType = Class.forName("net.md_5.bungee.api.chat.ChatMessageType");
        actionBarMessageType = chatMessageType.getField("ACTION_BAR").get(null);
        baseComponentClass = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
        Class<?> textComponentClass = Class.forName("net.md_5.bungee.api.chat.TextComponent");
        fromLegacyMethod = textComponentClass.getMethod("fromLegacyText", String.class);

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
        reflectionResolved = true;
    }
}
