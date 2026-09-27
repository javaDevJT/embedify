package tech.javadevjt.embedify.calendar;

import java.util.List;

public record EventsResponse(
        String title,
        List<CalendarEvent> events,
        String fetchedAt,
        int refreshSeconds,
        boolean truncated) {
    public EventsResponse {
        events = List.copyOf(events);
    }
}
