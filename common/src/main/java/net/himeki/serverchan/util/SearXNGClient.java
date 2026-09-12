package net.himeki.serverchan.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.himeki.serverchan.ServerChanCore;

import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Web search tool backend backed by a self-hosted SearXNG instance
 * (JSON output format). The instance URL template is configurable.
 *
 * Implemented on HttpURLConnection (no extra HTTP client dependency) so it stays
 * compatible with every build profile, including the Java 8 ones (MC 1.12-1.16).
 */
public final class SearXNGClient {

    private static final int HTTP_TIMEOUT_MS = 10_000;
    private static final int MAX_SNIPPET_LENGTH = 300;

    private SearXNGClient() {}

    /**
     * Search the configured SearXNG instance and format the top results
     * as a compact text block for the LLM.
     */
    public static String search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "Web search error: empty query";
        }

        if (!ServerChanCore.CONFIG.webSearchEnabled) {
            return "Web search is disabled in the config";
        }

        String template = ServerChanCore.CONFIG.webSearchUrl;
        if (template == null || template.trim().isEmpty()) {
            return "Web search error: URL is not configured";
        }

        String encodedQuery;
        try {
            encodedQuery = URLEncoder.encode(query.trim(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "Web search error: UTF-8 not supported"; // Cannot happen on any JVM
        }
        String url = template.replace("{query}", encodedQuery);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ServerChan/1.0 (Minecraft server bot)");

            int status = connection.getResponseCode();
            if (status != 200) {
                return "Web search failed: HTTP " + status;
            }

            JsonElement root;
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                root = new JsonParser().parse(reader);
            }
            return formatResults(parseResults(root));
        } catch (Exception e) {
            ServerChanCore.LOGGER.warn("Web search failed for query '{}': {}", query, e.getMessage());
            return "Web search failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Extracts the 'results' array from the SearXNG JSON payload. */
    private static JsonArray parseResults(JsonElement root) {
        JsonObject rootObject = root.getAsJsonObject();
        JsonArray results = rootObject.getAsJsonArray("results");
        return results != null ? results : new JsonArray();
    }

    /** Formats up to webSearchMaxResults results (title, url, content) for the model. */
    private static String formatResults(JsonArray results) {
        int max = Math.max(1, ServerChanCore.CONFIG.webSearchMaxResults);
        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (JsonElement element : results) {
            if (count >= max) {
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();

            String title = getAsString(item, "title");
            String url = getAsString(item, "url");
            String content = getAsString(item, "content");
            if (content.length() > MAX_SNIPPET_LENGTH) {
                content = content.substring(0, MAX_SNIPPET_LENGTH) + "...";
            }

            sb.append(++count).append(". ").append(title).append('\n');
            if (!url.isEmpty()) {
                sb.append("URL: ").append(url).append('\n');
            }
            if (!content.isEmpty()) {
                sb.append(content).append('\n');
            }
            sb.append('\n');
        }

        if (count == 0) {
            return "Web search returned no results";
        }
        return sb.toString().trim();
    }

    private static String getAsString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
