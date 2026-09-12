package net.himeki.serverchan.openai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Function definition for the long-term memory tool.
 * Facts are stored per player in SQLite and injected into the system prompt.
 */
@JsonTypeName("remember_fact")
@JsonClassDescription("Remember a lasting fact about the current player in long-term memory (SQLite). Use for stable personal facts the player tells you (favorite block, real name, preferences, promises). Do NOT use for temporary chat context.")
public class RememberFactTool {
    @JsonPropertyDescription("Short fact key, e.g. 'favorite_block' or 'birthday'")
    public String key;

    @JsonPropertyDescription("The fact value to remember, e.g. 'diamond sword'")
    public String value;

    public RememberFactTool() {}

    public RememberFactTool(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
