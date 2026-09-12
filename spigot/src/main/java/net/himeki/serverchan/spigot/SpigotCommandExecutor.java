package net.himeki.serverchan.spigot;

import net.himeki.serverchan.CommandExecutor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Spigot implementation of CommandExecutor using Bukkit API
 */
public class SpigotCommandExecutor implements CommandExecutor {
    private final JavaPlugin plugin;
    private volatile Method commandMapMethod;

    public SpigotCommandExecutor(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String executeCommand(String command, int permissionLevel) {
        if (!isReady()) {
            return "Server not ready";
        }

        // Execute command on main thread and capture output
        CompletableFuture<String> future = new CompletableFuture<>();

        // Create a new buffer for this specific execution to avoid concurrency issues
        // Each command execution gets its own isolated buffer
        final StringBuffer outputBuffer = new StringBuffer();
        final StringBuffer consoleBuffer = new StringBuffer();

        Bukkit.getScheduler().runTask(plugin, () -> {
            // Vanilla (Brigadier) feedback goes to the server console, not to the
            // dispatching sender - capture the console log for the dispatch window
            SpigotConsoleCapture consoleCapture = SpigotConsoleCapture.attach(consoleBuffer);
            try {
                // Create sender with isolated buffer for this execution
                CommandSender sender = new ServerChanCommandSender(outputBuffer, permissionLevel);

                // Execute the command
                boolean success = Bukkit.dispatchCommand(sender, command);

                // Get captured output
                String output = outputBuffer.toString().trim();

                if (!success && output.isEmpty()) {
                    output = "Command failed: " + command;
                }

                // Plugin commands shadow vanilla ones (e.g. EssentialsX's /time has no 'query'
                // subcommand, so vanilla-style arguments only produce a help screen). When the
                // first attempt was useless, try the vanilla command directly, and as a last
                // resort route it through "execute run", which resolves inside vanilla Brigadier.
                if (!success || looksLikeUsageHelp(output)) {
                    String name = command.startsWith("/") ? command.substring(1) : command;
                    name = name.split(" ")[0].toLowerCase(Locale.ROOT);

                    org.bukkit.command.Command vanilla = findVanillaCommand(name);
                    if (vanilla != null) {
                        StringBuffer vanillaBuffer = new StringBuffer();
                        CommandSender vanillaSender = new ServerChanCommandSender(vanillaBuffer, permissionLevel);
                        boolean vanillaSuccess = vanilla.execute(vanillaSender, name, splitArgs(command));
                        String vanillaOutput = vanillaBuffer.toString().trim();
                        if (vanillaSuccess && !vanillaOutput.isEmpty()) {
                            output = vanillaOutput;
                        }
                    } else if (!name.isEmpty() && !name.contains(":")) {
                        StringBuffer executeBuffer = new StringBuffer();
                        CommandSender executeSender = new ServerChanCommandSender(executeBuffer, permissionLevel);
                        // "execute run <cmd>" is parsed by vanilla Brigadier directly, so the
                        // plugin shadow on the plain name is irrelevant here
                        boolean executeSuccess = Bukkit.dispatchCommand(executeSender, "execute run " + command);
                        String executeOutput = executeBuffer.toString().trim();
                        if (executeSuccess && !executeOutput.isEmpty()) {
                            output = executeOutput;
                        }
                    }
                }

                // Prefer the captured console output (it contains the real vanilla feedback);
                // fall back to the sender buffer when the capture is unavailable or empty
                String consoleOutput = consoleBuffer.toString().trim();
                if (!consoleOutput.isEmpty()) {
                    output = consoleOutput;
                }

                future.complete(output);
            } catch (Exception e) {
                // Surface the root cause (e.g. Brigadier's "Incorrect argument..." text)
                // to the model instead of the generic wrapper message
                String message = rootCauseMessage(e);
                plugin.getLogger().warning("Command '" + command + "' failed: " + message);
                future.complete("Error: " + message);
            } finally {
                consoleCapture.detach();
            }
        });

        try {
            // Wait for command execution with timeout
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Command execution timed out: " + command);
            return "Command execution timed out";
        }
    }

    /** Strips the leading command name, returning only its arguments. */
    private static String[] splitArgs(String command) {
        String clean = command.startsWith("/") ? command.substring(1) : command;
        String[] parts = clean.split(" ");
        String[] args = new String[Math.max(0, parts.length - 1)];
        System.arraycopy(parts, 1, args, 0, args.length);
        return args;
    }

    private static String rootCauseMessage(Throwable error) {
        String message = error.getMessage();
        Throwable current = error.getCause();
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message != null ? message : error.getClass().getSimpleName();
    }

    /** Localized "usage/help was printed instead of running" detection (vanilla + common plugins). */
    private static boolean looksLikeUsageHelp(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("usage:")
                || lower.contains("correct usage")
                || lower.contains("справка по команде")
                || lower.contains("использование:");
    }

    /**
     * Finds the vanilla Brigadier command wrapper for a command name that a plugin has
     * shadowed (e.g. EssentialsX's /time). Vanilla commands are registered as
     * VanillaCommandWrapper and stay reachable in the command map even after a plugin
     * takes over the plain name. Uses only the public CommandMap API - no internals.
     */
    private org.bukkit.command.Command findVanillaCommand(String name) {
        if (name.isEmpty() || name.contains(":")) {
            return null;
        }
        try {
            org.bukkit.command.CommandMap map = getCommandMap();
            if (map == null) {
                return null;
            }
            org.bukkit.command.Command namespaced = map.getCommand("minecraft:" + name);
            if (isVanillaWrapper(namespaced)) {
                return namespaced;
            }
            org.bukkit.command.Command plain = map.getCommand(name);
            if (isVanillaWrapper(plain)) {
                return plain;
            }
        } catch (Throwable ignored) {
            // Command map not accessible - direct vanilla fallback unavailable
        }
        return null;
    }

    private org.bukkit.command.CommandMap getCommandMap() throws Exception {
        Method getter = commandMapMethod;
        if (getter == null) {
            getter = Bukkit.getServer().getClass().getMethod("getCommandMap");
            commandMapMethod = getter;
        }
        return (org.bukkit.command.CommandMap) getter.invoke(Bukkit.getServer());
    }

    private static boolean isVanillaWrapper(org.bukkit.command.Command command) {
        return command != null && command.getClass().getName().contains("VanillaCommandWrapper");
    }

    @Override
    public boolean isReady() {
        return plugin != null && plugin.isEnabled() && Bukkit.getServer() != null;
    }

    /**
     * Custom CommandSender implementation that provides better compatibility
     * by implementing ConsoleCommandSender interface
     */
    private static class ServerChanCommandSender implements ConsoleCommandSender {
        private final StringBuffer outputBuffer;
        private final int permissionLevel;
        private final ConsoleCommandSender consoleSender;

        public ServerChanCommandSender(StringBuffer buffer, int permissionLevel) {
            this.outputBuffer = buffer;
            this.permissionLevel = permissionLevel;
            this.consoleSender = Bukkit.getConsoleSender();
        }

        @Override
        public void sendMessage(String message) {
            if (message != null) {
                outputBuffer.append(message).append("\n");
            }
        }

        @Override
        public void sendMessage(String... messages) {
            for (String message : messages) {
                sendMessage(message);
            }
        }

        #if MC_VER >= MC_1_16
        @Override
        public void sendMessage(UUID sender, String message) {
            sendMessage(message);
        }

        @Override
        public void sendMessage(UUID sender, String... messages) {
            sendMessage(messages);
        }
        #endif

        @Override
        public org.bukkit.Server getServer() {
            return Bukkit.getServer();
        }

        @Override
        public String getName() {
            return "ServerChan";
        }

        @Override
        public boolean isPermissionSet(String name) {
            return true;
        }

        @Override
        public boolean isPermissionSet(Permission perm) {
            return true;
        }

        @Override
        public boolean hasPermission(String name) {
            // Grant permissions based on level (0-4, where 4 is op)
            return permissionLevel >= 2;
        }

        @Override
        public boolean hasPermission(Permission perm) {
            return permissionLevel >= 2;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
            return consoleSender.addAttachment(plugin, name, value);
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin) {
            return consoleSender.addAttachment(plugin);
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
            return consoleSender.addAttachment(plugin, name, value, ticks);
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
            return consoleSender.addAttachment(plugin, ticks);
        }

        @Override
        public void removeAttachment(PermissionAttachment attachment) {
            consoleSender.removeAttachment(attachment);
        }

        @Override
        public void recalculatePermissions() {
            consoleSender.recalculatePermissions();
        }

        @Override
        public Set<PermissionAttachmentInfo> getEffectivePermissions() {
            return consoleSender.getEffectivePermissions();
        }

        @Override
        public boolean isOp() {
            return permissionLevel >= 4;
        }

        @Override
        public void setOp(boolean value) {
            // No-op for safety
        }

        @Override
        public org.bukkit.command.CommandSender.Spigot spigot() {
            return consoleSender.spigot();
        }

        // ConsoleCommandSender specific methods

        @Override
        public boolean isConversing() {
            return consoleSender.isConversing();
        }

        @Override
        public void acceptConversationInput(String input) {
            consoleSender.acceptConversationInput(input);
        }

        @Override
        public boolean beginConversation(Conversation conversation) {
            return consoleSender.beginConversation(conversation);
        }

        @Override
        public void abandonConversation(Conversation conversation) {
            consoleSender.abandonConversation(conversation);
        }

        @Override
        public void abandonConversation(Conversation conversation, ConversationAbandonedEvent details) {
            consoleSender.abandonConversation(conversation, details);
        }

        @Override
        public void sendRawMessage(String message) {
            sendMessage(message);
        }

        #if MC_VER >= MC_1_16
        @Override
        public void sendRawMessage(UUID sender, String message) {
            sendMessage(message);
        }
        #endif
    }
}