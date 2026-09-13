package net.himeki.serverchan.openai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Function definition for the live player info tool.
 */
@JsonTypeName("get_player_info")
@JsonClassDescription("Get live info about an online player: world, coordinates, health, hunger, gamemode, ping and playtime. Leave player_name empty to get a list of all online players.")
public class GetPlayerInfoTool {
    @JsonPropertyDescription("Exact player name to inspect; leave empty for all online players")
    public String player_name;

    public GetPlayerInfoTool() {}

    public GetPlayerInfoTool(String player_name) {
        this.player_name = player_name;
    }
}
