package net.himeki.serverchan.util;

import net.himeki.serverchan.ServerChanCore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Long-term memory for the AI assistant, backed by a local SQLite database
 * (plugins/ServerChan/memory.db or the platform config directory equivalent).
 *
 * Facts are stored per player so they can be injected into the system prompt
 * on every request. Uses only java.sql, so the common module does not need the
 * SQLite driver at compile time - it is shaded into the final jars at runtime.
 */
public final class MemoryManager {

    private static final String DRIVER_CLASS = "org.sqlite.JDBC";
    /** How many facts per player are injected into the system prompt on each request. */
    private static final int MAX_INJECTED_FACTS = 25;
    /** Hard cap of stored facts per player - keeps memory.db from growing unbounded. */
    private static final int MAX_STORED_FACTS_PER_PLAYER = 100;

    private static volatile boolean initialized = false;
    private static Connection connection;

    private MemoryManager() {}

    /**
     * Initialize the memory database inside the given plugin data directory.
     * Safe to call multiple times; failures are logged and memory is disabled.
     */
    public static synchronized void initialize(Path dataDir) {
        if (initialized) {
            return;
        }

        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            ServerChanCore.LOGGER.error("SQLite JDBC driver not found - long-term memory will be disabled", e);
            return;
        }

        try {
            Files.createDirectories(dataDir);
            Path dbFile = dataDir.resolve("memory.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_facts (" +
                    "player_uuid TEXT NOT NULL, " +
                    "player_name TEXT, " +
                    "key TEXT NOT NULL, " +
                    "value TEXT, " +
                    "updated_at TEXT NOT NULL, " +
                    "PRIMARY KEY (player_uuid, key))"
                );
            }

            initialized = true;
            ServerChanCore.LOGGER.info("Long-term memory initialized at {}", dbFile.toAbsolutePath());
        } catch (Exception e) {
            ServerChanCore.LOGGER.error("Failed to initialize long-term memory database", e);
            connection = null;
        }
    }

    /** @return true if the memory database is available. */
    public static boolean isAvailable() {
        return initialized && connection != null;
    }

    /**
     * Store (or overwrite) a fact about a player.
     *
     * @return true if the fact was saved.
     */
    public static synchronized boolean rememberFact(UUID playerUuid, String playerName, String key, String value) {
        if (!isAvailable() || playerUuid == null || key == null || key.trim().isEmpty()) {
            return false;
        }
        if (value == null) {
            value = "";
        }
        // Clamp unreasonably large entries so the context doesn't blow up
        if (key.length() > 200) {
            key = key.substring(0, 200);
        }
        if (value.length() > 2000) {
            value = value.substring(0, 2000);
        }

        String sql = "INSERT OR REPLACE INTO player_facts (player_uuid, player_name, key, value, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, playerName);
            ps.setString(3, key.trim());
            ps.setString(4, value.trim());
            ps.setString(5, Timestamp.valueOf(LocalDateTime.now()).toString());
            ps.executeUpdate();
        } catch (Exception e) {
            ServerChanCore.LOGGER.error("Failed to save memory fact for {}", playerUuid, e);
            return false;
        }

        // Keep only the newest MAX_STORED_FACTS_PER_PLAYER facts for this player,
        // so a chatty model inventing endless unique keys can't bloat the database
        String prune = "DELETE FROM player_facts WHERE player_uuid = ? AND key NOT IN " +
                       "(SELECT key FROM player_facts WHERE player_uuid = ? " +
                       "ORDER BY updated_at DESC, rowid DESC LIMIT ?)";
        try (PreparedStatement ps = connection.prepareStatement(prune)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, playerUuid.toString());
            ps.setInt(3, MAX_STORED_FACTS_PER_PLAYER);
            ps.executeUpdate();
        } catch (Exception e) {
            ServerChanCore.LOGGER.debug("Failed to prune memory facts for {}", playerUuid, e);
        }
        return true;
    }

    /**
     * Load all facts stored about a player, newest first.
     *
     * @return list of "key: value" lines; empty list if none or unavailable.
     */
    public static synchronized List<String> getFacts(UUID playerUuid) {
        List<String> facts = new ArrayList<>();
        if (!isAvailable() || playerUuid == null) {
            return facts;
        }

        String sql = "SELECT key, value FROM player_facts WHERE player_uuid = ? " +
                     "ORDER BY updated_at DESC LIMIT " + MAX_INJECTED_FACTS;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    facts.add(rs.getString("key") + ": " + rs.getString("value"));
                }
            }
        } catch (Exception e) {
            ServerChanCore.LOGGER.error("Failed to load memory facts for {}", playerUuid, e);
        }
        return facts;
    }

    /** Close the database connection (call on plugin shutdown). */
    public static synchronized void shutdown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                ServerChanCore.LOGGER.debug("Error closing memory database", e);
            }
            connection = null;
        }
        initialized = false;
    }
}
