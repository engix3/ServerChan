package net.himeki.serverchan;

import net.himeki.serverchan.config.ServerChanConfigBase;
import net.himeki.serverchan.config.ConfigLoader;
import net.himeki.serverchan.i18n.I18n;
import net.himeki.serverchan.openai.OpenAIHandler;
import net.himeki.serverchan.util.ChatFormat;
import net.himeki.serverchan.util.KotlinReflectionWorkaround;
import net.himeki.serverchan.util.MemoryManager;
import net.himeki.serverchan.util.ServerInfoProvider;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

public class ServerChanCore {
    public static final String MOD_ID = "serverchan";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static volatile ServerChanConfigBase CONFIG;
    private static MessageBroadcaster messageBroadcaster;
    private static CommandExecutor commandExecutor;
    private static volatile ServerInfoProvider serverInfoProvider;
    private static volatile boolean enabled = true;
    public static final java.util.List<String> executedCommandsForTesting = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Initialize the core components
     */
    public static void initialize(ServerChanConfigBase config) {
        CONFIG = config;
        enabled = CONFIG.enabled;

        KotlinReflectionWorkaround.ensureKotlinReflectionFactory();

        // Initialize i18n - detect system locale or use config
        I18n.setSystemLocale();

        // Update locale from config
        I18n.updateLocaleFromConfig(CONFIG.locale);

        // Initialize the SQLite-backed long-term memory if enabled
        if (CONFIG.memoryEnabled) {
            MemoryManager.initialize(getDataDirectory());
        }

        LOGGER.info(I18n.get("serverchan.startup"));

        // Initialize OpenAI
        OpenAIHandler.initializeOpenAI();
    }

    /**
     * Data directory used by platform-independent subsystems (e.g. the memory database).
     * Platforms that support it override this via {@link #setDataDirectory(java.nio.file.Path)}.
     */
    private static volatile java.nio.file.Path dataDirectory;
    private static volatile boolean dataDirectoryResolved = false;

    /**
     * Set the platform data directory (e.g. plugins/ServerChan on Bukkit).
     * Must be called before {@link #initialize(ServerChanConfigBase)}.
     */
    public static void setDataDirectory(java.nio.file.Path path) {
        dataDirectory = path;
        dataDirectoryResolved = true;
    }

    /**
     * Get the platform data directory; falls back to the current working directory.
     */
    public static java.nio.file.Path getDataDirectory() {
        if (dataDirectoryResolved && dataDirectory != null) {
            return dataDirectory;
        }
        return java.nio.file.Paths.get("").toAbsolutePath();
    }

    /**
     * Set the message broadcaster for the current platform
     */
    public static void setMessageBroadcaster(MessageBroadcaster broadcaster) {
        messageBroadcaster = broadcaster;
    }

    /**
     * Get the message broadcaster
     */
    public static MessageBroadcaster getMessageBroadcaster() {
        return messageBroadcaster;
    }

    /**
     * Set the command executor for the current platform
     */
    public static void setCommandExecutor(CommandExecutor executor) {
        commandExecutor = executor;
    }

    /**
     * Get the command executor
     */
    public static CommandExecutor getCommandExecutor() {
        return commandExecutor;
    }

    /**
     * Set the platform server info provider (used by the get_server_metrics tool)
     */
    public static void setServerInfoProvider(ServerInfoProvider provider) {
        serverInfoProvider = provider;
    }

    /**
     * Get the platform server info provider (may be null if the platform doesn't provide one)
     */
    public static ServerInfoProvider getServerInfoProvider() {
        return serverInfoProvider;
    }

    /**
     * Format a message the bot broadcasts: translate '&' codes to '§'
     * and prepend the configurable bot prefix (without duplicating it).
     */
    public static String formatForChat(String message) {
        return ChatFormat.formatBotMessage(message);
    }

    /**
     * Handle chat messages from players
     *
     * @param playerName Player's name
     * @param message The chat message
     * @param permissionLevel The player's permission level
     */
    public static void onChatMessage(String playerName, String message, int permissionLevel) {
        onChatMessage(null, playerName, message, permissionLevel);
    }

    /**
     * Handle chat messages from players
     *
     * @param playerUuid Player's unique ID (used for long-term memory and metrics), may be null
     * @param playerName Player's name
     * @param message The chat message
     * @param permissionLevel The player's permission level
     */
    public static void onChatMessage(UUID playerUuid, String playerName, String message, int permissionLevel) {
        if (!enabled) {
            return;
        }

        processAIResponseAsync(playerUuid, playerName, message, permissionLevel)
                .thenAccept(response -> {
                    if (!enabled) {
                        return;
                    }

                    // Only broadcast if AI decided to respond (response is not null or empty)
                    if (response != null && !response.isEmpty() && messageBroadcaster != null && messageBroadcaster.isReady()) {
                        messageBroadcaster.broadcastMessage(formatForChat(response));
                    }
                });
    }

    /**
     * Handle game events (join/leave, deaths, etc.)
     *
     * @param eventKey The event key (e.g., "death.attack.player")
     * @param translatedMessage The translated event message
     */
    public static void onGameEvent(String eventKey, String translatedMessage) {
        if (!enabled) {
            return;
        }

        if (!shouldProcessEvent(eventKey)) {
            return;
        }

        try {
            CompletableFuture.runAsync(() -> {
                if (!enabled) {
                    return;
                }

                ZoneId zoneId;
                try {
                    zoneId = ZoneId.of(CONFIG.timeZone);
                } catch (Exception e) {
                    zoneId = ZoneId.systemDefault();
                    LOGGER.error("Invalid time zone in config, using system default", e);
                }
                ZonedDateTime zonedDateTime = ZonedDateTime.now(zoneId);
                String formattedDateTime = zonedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                String requestMessage = "[" + formattedDateTime + "] " + translatedMessage;

                if (!enabled) {
                    return;
                }

                String aiResponse = OpenAIHandler.getEventResponse("Systems", requestMessage, 0);

                // Check if response contains the no_message token
                if (OpenAIHandler.isNoMessageResponse(aiResponse)) {
                    // AI decided not to respond to this event
                    return;
                }

                // Only broadcast if there's a valid response
                if (enabled && aiResponse != null && !aiResponse.isEmpty() && messageBroadcaster != null && messageBroadcaster.isReady()) {
                    messageBroadcaster.broadcastMessage(formatForChat(aiResponse));
                }
            }, OpenAIHandler.getAsyncExecutor());
        } catch (RejectedExecutionException e) {
            LOGGER.debug("Skipping game event {} because async executor is shutting down", eventKey);
        }
    }

    private static CompletableFuture<String> processAIResponseAsync(UUID playerUuid, String sender, String message, int permissionLevel) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                if (!enabled) {
                    return null;
                }

                ZoneId zoneId;
                try {
                    zoneId = ZoneId.of(CONFIG.timeZone);
                } catch (Exception e) {
                    zoneId = ZoneId.systemDefault();
                    LOGGER.error("Invalid time zone in config, using system default", e);
                }
                ZonedDateTime zonedDateTime = ZonedDateTime.now(zoneId);
                String formattedDateTime = zonedDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                String requestMessage = "[" + formattedDateTime + "] " + "<" + sender + ">: " + message;

                String response = OpenAIHandler.getChatResponse(playerUuid, sender, requestMessage, permissionLevel);

                if (!enabled) {
                    return null;
                }

                // Check if response contains the no_message token
                if (OpenAIHandler.isNoMessageResponse(response)) {
                    // AI decided not to respond, return null
                    return null;
                }

                // Clean up the response if it's not null
                if (response != null) {
                    return response.replaceAll("\\n{2,}", "\n").trim();
                }

                return response;
            }, OpenAIHandler.getAsyncExecutor());
        } catch (RejectedExecutionException e) {
            LOGGER.debug("Skipping chat message processing because async executor is shutting down");
            return CompletableFuture.completedFuture(null);
        }
    }

    private static boolean shouldProcessEvent(String key) {
        if (!CONFIG.enableGameEvents) {
            return false;
        }

        if (key.startsWith("multiplayer.player.joined") || key.startsWith("multiplayer.player.left")) {
            return CONFIG.enableJoinLeaveEvents;
        }

        if (key.startsWith("death.")) {
            return CONFIG.enableDeathEvents;
        }

        return true;
    }

    /**
     * Shutdown the OpenAI handler
     */
    public static void shutdown() {
        enabled = false;
        OpenAIHandler.shutdown();
        MemoryManager.shutdown();
    }

    /**
     * Reload the configuration
     */
    public static void reloadConfig(ServerChanConfigBase newConfig) {
        CONFIG = newConfig;
        enabled = CONFIG.enabled;
        I18n.updateLocaleFromConfig(CONFIG.locale);
        LOGGER.info(I18n.get("serverchan.config.reloaded"));
    }

    /**
     * Enable ServerChan message processing
     * @return Status message to display
     */
    public static String executeEnable() {
        if (enabled) {
            return I18n.get("command.enable.already");
        }

        enabled = true;
        CONFIG.enabled = true;
        ConfigLoader.updateEnabledState(true);
        LOGGER.info("ServerChan enabled via command");
        return I18n.get("command.enable.success");
    }

    /**
     * Disable ServerChan message processing
     * @return Status message to display
     */
    public static String executeDisable() {
        if (!enabled) {
            return I18n.get("command.disable.already");
        }

        enabled = false;
        CONFIG.enabled = false;
        ConfigLoader.updateEnabledState(false);
        LOGGER.info("ServerChan disabled via command");
        return I18n.get("command.disable.success");
    }

    /**
     * Execute reload command - reloads configuration and reinitializes OpenAI
     * @return Success message to display
     */
    public static String executeReload() {
        // Note: Config reload itself should be handled by platform-specific code
        // This method handles the common logic after config is reloaded
        OpenAIHandler.initializeOpenAI();
        return I18n.get("command.reload.success");
    }

    /**
     * Execute reset command - resets message context
     * @return Success message to display
     */
    public static String executeReset() {
        OpenAIHandler.resetMessageContext();
        return I18n.get("command.reset.success");
    }

    /**
     * Execute kill command - resets the OpenAI client
     * @return Success message to display
     */
    public static String executeKill() {
        OpenAIHandler.resetClient();
        return I18n.get("command.kill.success");
    }

    /**
     * Check if ServerChan is currently processing messages
     */
    public static boolean isEnabled() {
        return enabled;
    }
}
