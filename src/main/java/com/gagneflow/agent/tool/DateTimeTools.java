package com.gagneflow.agent.tool;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
public class DateTimeTools {
    public static final String TOOL_GET_CURRENT_DATETIME = "getCurrentDateTime";

    @Tool(description="Get the current date and time in the user's timezone")
    public String getCurrentDateTime() {
        TimeZone timeZone = LocaleContextHolder.getTimeZone();
        ZoneId zoneId = timeZone != null ? timeZone.toZoneId() : ZoneId.systemDefault();
        return LocalDateTime.now().atZone(zoneId).toString();
    }
}
