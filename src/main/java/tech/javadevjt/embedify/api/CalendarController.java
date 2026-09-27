package tech.javadevjt.embedify.api;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.javadevjt.embedify.calendar.CalendarFeedService;
import tech.javadevjt.embedify.calendar.EventsResponse;

import java.time.LocalDate;
import java.util.List;

@RestController
public class CalendarController {
    private final CalendarFeedService feedService;

    public CalendarController(CalendarFeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/api/events")
    public EventsResponse events(
            @RequestParam(name = "feed") List<String> feeds,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "tz", required = false, defaultValue = "UTC") String timezone) {
        return feedService.events(feeds, from, to, timezone);
    }
}
