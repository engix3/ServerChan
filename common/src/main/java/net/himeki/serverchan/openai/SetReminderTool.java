package net.himeki.serverchan.openai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Function definition for the reminder tool.
 * Reminders are kept in memory: they fire while the server is running and are
 * lost on restart (documented in the description so the model can tell players).
 */
@JsonTypeName("set_reminder")
@JsonClassDescription("Set a reminder for the current player. It fires as a chat message after the given delay. Reminders are in-memory and do not survive a server restart.")
public class SetReminderTool {
    @JsonPropertyDescription("Delay in minutes before the reminder fires (1-1440)")
    public int delay_minutes;

    @JsonPropertyDescription("What to remind about, e.g. 'check the furnace' or 'switch the farm off'")
    public String text;

    public SetReminderTool() {}

    public SetReminderTool(int delay_minutes, String text) {
        this.delay_minutes = delay_minutes;
        this.text = text;
    }
}
