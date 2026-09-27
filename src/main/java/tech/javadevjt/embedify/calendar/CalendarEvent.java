package tech.javadevjt.embedify.calendar;

public record CalendarEvent(
        String id,
        String title,
        String start,
        String end,
        boolean allDay,
        String location,
        String description,
        String url,
        int feedIndex) {
}
