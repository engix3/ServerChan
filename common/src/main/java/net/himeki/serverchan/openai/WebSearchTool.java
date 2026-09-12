package net.himeki.serverchan.openai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Function definition for the web search tool (backed by a SearXNG instance).
 */
@JsonTypeName("web_search")
@JsonClassDescription("Search the web for up-to-date information using a SearXNG metasearch instance. Returns the top results with titles, URLs and snippets.")
public class WebSearchTool {
    @JsonPropertyDescription("The search query, e.g. 'Minecraft 1.21 patch notes'")
    public String query;

    public WebSearchTool() {}

    public WebSearchTool(String query) {
        this.query = query;
    }
}
