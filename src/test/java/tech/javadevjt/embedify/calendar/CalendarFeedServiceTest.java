package tech.javadevjt.embedify.calendar;

import net.fortuna.ical4j.model.TimeZoneUpdater;
import net.fortuna.ical4j.util.Configurator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarFeedServiceTest {
 private final CalendarFeedService service = new CalendarFeedService(null);

    @Test
    void readsFoldedEventSummariesWithLfOrCrLfLineEndings() {
        StringBuilder source = new StringBuilder("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Embedify//Test//EN\n");
        for (int index = 0; index < 16; index++) {
            source.append("BEGIN:VEVENT\nUID:shift-").append(index).append("\n")
                    .append("DTSTART;VALUE=DATE-TIME:20260928T120000Z\n")
                    .append("DTEND;VALUE=DATE-TIME:20260928T130000Z\n")
                    .append("SUMMARY:On-call rotation for the\n  operations team\nEND:VEVENT\n");
        }
        source.append("END:VCALENDAR\n");

        for (String newline : List.of("\n", "\r\n")) {
            var result = service.parseFeed(source.toString().replace("\n", newline),
                    LocalDate.parse("2026-09-28"), LocalDate.parse("2026-09-29"),
                    ZoneId.of("UTC"), 0, 500);
            assertEquals(16, result.events().size());
            assertTrue(result.events().stream()
                    .allMatch(event -> event.title().equals("On-call rotation for the operations team")));
            assertFalse(result.truncated());
        }
    }

 @Test
 void disablesIcal4jTimezoneUpdatesFromClasspathConfiguration() {
 assertEquals("false", Configurator.getProperty("net.fortuna.ical4j.timezone.update.enabled").orElseThrow());
 assertFalse(new TimeZoneUpdater().isEnabled());
 }

 @Test
    void reusesParsedResultForSameBodyAndRefreshesWhenBodyChanges() throws IOException {
        String source = readFixture("/calendarlabs-us-holidays.ics");
        LocalDate from = LocalDate.parse("2026-09-01");
        LocalDate to = LocalDate.parse("2026-10-13");
        ZoneId zone = ZoneId.of("America/Detroit");

        CalendarFeedService.FeedResult first = service.parseCachedFeed(source, from, to, zone, 0, 500);
        CalendarFeedService.FeedResult repeated = service.parseCachedFeed(source, from, to, zone, 0, 500);
        assertSame(first, repeated);

        CalendarFeedService.FeedResult narrower = service.parseCachedFeed(source,
                LocalDate.parse("2026-09-06"), LocalDate.parse("2026-09-08"), zone, 0, 500);
        assertNotSame(first, narrower);
        assertEquals(1, narrower.events().size());

        String changedSource = source.replace("SUMMARY:Labor Day\n", "SUMMARY:Labor Day 2026\n");
        CalendarFeedService.FeedResult refreshed = service.parseCachedFeed(changedSource, from, to, zone, 0, 500);
        assertNotSame(first, refreshed);
        assertTrue(refreshed.events().stream().anyMatch(event -> event.title().equals("Labor Day 2026")));
    }

    @Test
    void returnsTooManyRequestsWhenBothUncachedParsePermitsAreBusy() throws Exception {
        String source = readFixture("/calendarlabs-us-holidays.ics");
        LocalDate from = LocalDate.parse("2026-09-01");
        LocalDate to = LocalDate.parse("2026-10-13");
        ZoneId zone = ZoneId.of("America/Detroit");
        CountDownLatch parsersStarted = new CountDownLatch(2);
        CountDownLatch releaseParsers = new CountDownLatch(1);
        CalendarFeedService parsingService = new CalendarFeedService(null) {
            @Override
            FeedResult parseFeed(String content, LocalDate rangeStart, LocalDate rangeEnd, ZoneId timezone,
                                 int feedIndex, int limit) {
                parsersStarted.countDown();
                try {
                    if (!releaseParsers.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for test release.");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return super.parseFeed(content, rangeStart, rangeEnd, timezone, feedIndex, limit);
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CalendarFeedService.FeedResult> first = executor.submit(() -> parsingService.parseCachedFeed(
                    source.replace("SUMMARY:Labor Day", "SUMMARY:Labor Day A"), from, to, zone, 0, 500));
            Future<CalendarFeedService.FeedResult> second = executor.submit(() -> parsingService.parseCachedFeed(
                    source.replace("SUMMARY:Labor Day", "SUMMARY:Labor Day B"), from, to, zone, 0, 500));
            assertTrue(parsersStarted.await(5, TimeUnit.SECONDS));

            ResponseStatusException busy = assertThrows(ResponseStatusException.class,
                    () -> parsingService.parseCachedFeed(
                            source.replace("SUMMARY:Labor Day", "SUMMARY:Labor Day C"), from, to, zone, 0, 500));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, busy.getStatusCode());

            releaseParsers.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseParsers.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void normalizesZeroLengthCalendarLabsAllDayEventsToOneDay() throws IOException {
        String source = readFixture("/calendarlabs-us-holidays.ics");

        CalendarFeedService.FeedResult result = service.parseFeed(source,
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-13"), ZoneId.of("America/Detroit"), 0, 500);

        assertEquals(2, result.events().size());
        CalendarEvent laborDay = result.events().stream().filter(event -> event.title().equals("Labor Day"))
                .findFirst().orElseThrow();
        assertTrue(laborDay.allDay());
        assertEquals("2026-09-07", laborDay.start());
        assertEquals("2026-09-08", laborDay.end());

        CalendarEvent columbusDay = result.events().stream().filter(event -> event.title().equals("Columbus Day"))
                .findFirst().orElseThrow();
        assertTrue(columbusDay.allDay());
        assertEquals("2026-10-12", columbusDay.start());
        assertEquals("2026-10-13", columbusDay.end());
    }

    @Test
    void givesAllDayEventsWithoutAnEndOneDay() {
        String source = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:all-day-without-end
                DTSTART;VALUE=DATE:20261001
                SUMMARY:All-day without DTEND
                END:VEVENT
                END:VCALENDAR
                """;

        CalendarFeedService.FeedResult result = service.parseFeed(source,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"), ZoneId.of("UTC"), 0, 500);

        assertEquals(1, result.events().size());
        assertEquals("2026-10-01", result.events().getFirst().start());
        assertEquals("2026-10-02", result.events().getFirst().end());
    }

    @Test
    void expandsRecurrenceAndAppliesMovedAndCancelledOverridesAcrossTimezone() {
        String source = """
                BEGIN:VCALENDAR
                VERSION:2.0
                X-WR-CALNAME:Team calendar
                BEGIN:VEVENT
                UID:daily
                DTSTART;TZID=America/Detroit:20261001T090000
                DTEND;TZID=America/Detroit:20261001T100000
                RRULE:FREQ=DAILY;COUNT=5
                SUMMARY:Daily sync
                END:VEVENT
                BEGIN:VEVENT
                UID:daily
                RECURRENCE-ID;TZID=America/Detroit:20261002T090000
                DTSTART;TZID=America/Detroit:20261002T120000
                DTEND;TZID=America/Detroit:20261002T130000
                SUMMARY:Moved sync
                END:VEVENT
                BEGIN:VEVENT
                UID:daily
                RECURRENCE-ID;TZID=America/Detroit:20261003T090000
                DTSTART;TZID=America/Detroit:20261003T090000
                DTEND;TZID=America/Detroit:20261003T100000
                STATUS:CANCELLED
                SUMMARY:Cancelled sync
                END:VEVENT
                END:VCALENDAR
                """;

        CalendarFeedService.FeedResult result = service.parseFeed(source,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-07"),
                ZoneId.of("America/Detroit"), 0, 500);

        assertEquals("Team calendar", result.title());
        assertFalse(result.truncated());
        assertEquals(4, result.events().size(), result.events().toString());
        assertTrue(result.events().stream().anyMatch(event -> event.title().equals("Moved sync")
                && event.start().equals("2026-10-02T16:00:00Z")));
        assertFalse(result.events().stream().anyMatch(event -> event.title().equals("Cancelled sync")));
    }

    @Test
    void keepsAllDayExclusiveEndAndUsesRequestedTimezoneForFloatingTimes() {
        String source = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:conference
                DTSTART;VALUE=DATE:20261001
                DTEND;VALUE=DATE:20261003
                SUMMARY:Conference
                END:VEVENT
                BEGIN:VEVENT
                UID:floating
                DTSTART:20261002T090000
                DTEND:20261002T100000
                SUMMARY:Floating meeting
                END:VEVENT
                END:VCALENDAR
                """;

        CalendarFeedService.FeedResult result = service.parseFeed(source,
                LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-03"),
                ZoneId.of("America/Detroit"), 0, 500);

        CalendarEvent allDay = result.events().stream().filter(CalendarEvent::allDay).findFirst().orElseThrow();
        assertEquals("2026-10-01", allDay.start());
        assertEquals("2026-10-03", allDay.end());
        CalendarEvent floating = result.events().stream().filter(event -> event.title().equals("Floating meeting"))
                .findFirst().orElseThrow();
        assertEquals("2026-10-02T13:00:00Z", floating.start());
        assertEquals("2026-10-02T14:00:00Z", floating.end());
    }

    @Test
    void boundsAggressiveRecurrenceRulesAndMarksTruncated() {
        String source = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:secondly
                DTSTART:20261001T090000Z
                DTEND:20261001T090001Z
                RRULE:FREQ=SECONDLY
                SUMMARY:Aggressive
                END:VEVENT
                END:VCALENDAR
                """;

        CalendarFeedService.FeedResult result = service.parseFeed(source,
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"), ZoneId.of("UTC"), 0, 500);

        assertTrue(result.truncated());
        assertEquals(1, result.events().size());
        assertEquals("Aggressive", result.events().getFirst().title());
    }

    private static String readFixture(String resourceName) throws IOException {
        try (InputStream input = CalendarFeedServiceTest.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("Missing test fixture: " + resourceName);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
