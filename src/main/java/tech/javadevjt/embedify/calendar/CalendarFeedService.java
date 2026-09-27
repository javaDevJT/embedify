package tech.javadevjt.embedify.calendar;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Dur;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Uid;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tech.javadevjt.embedify.net.SafeFetcher;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CalendarFeedService {
    private static final int MAX_FEEDS = 5;
    private static final int MAX_RANGE_DAYS = 93;
    private static final int MAX_FEED_BYTES = 1024 * 1024;
    private static final long MAX_PARSED_FEED_CACHE_WEIGHT = 8L * 1024 * 1024;
    private static final int MAX_COMPONENTS = 300;
    private static final int MAX_OCCURRENCES_PER_FEED = 500;
    private static final int MAX_OCCURRENCES_PER_GROUP = 2500;
    private static final int MAX_OCCURRENCES_PER_RESPONSE = 1500;
    private static final int MAX_RECURRENCE_TEXT_CHARS = 8192;
    private static final Pattern EVENT_START = Pattern.compile("(?im)^BEGIN:VEVENT\\s*$");
    private static final Pattern UNSAFE_RECURRENCE_PART = Pattern.compile("(?i)(?:^|;)\\s*(?:BYSECOND|BYMINUTE)\\s*=");

    static {
        // Cap work per recurrence rule while still allowing calendars with a long-lived daily series.
        System.setProperty(Recur.KEY_MAX_INCREMENT_COUNT, "10000");
    }

    private final SafeFetcher fetcher;
    private final Semaphore parsePermits = new Semaphore(2);
    private final Cache<ParsedFeedKey, CachedParsedFeed> parsedFeedCache = Caffeine
            .<ParsedFeedKey, CachedParsedFeed>newBuilder()
            .maximumWeight(MAX_PARSED_FEED_CACHE_WEIGHT)
            .weigher((ParsedFeedKey key, CachedParsedFeed cached) -> cached.weight())
            .build();

    public CalendarFeedService(SafeFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public EventsResponse events(List<String> feeds, LocalDate from, LocalDate to, String timezone) {
        if (feeds == null || feeds.isEmpty() || feeds.size() > MAX_FEEDS) {
            throw badRequest("Provide between one and five calendar feeds.");
        }
        if (from == null || to == null || !to.isAfter(from)
                || ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw badRequest("Choose a date range of 1 to 93 days; the end date is exclusive.");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (RuntimeException exception) {
            throw badRequest("The timezone is invalid.");
        }

        List<CalendarEvent> events = new ArrayList<>();
        String title = "Calendar";
        boolean truncated = false;
        for (int index = 0; index < feeds.size(); index++) {
            String feedUrl = feeds.get(index);
            if (feedUrl == null || feedUrl.length() > 2048) {
                throw badRequest("A calendar URL is invalid or too long.");
            }
            SafeFetcher.FetchResult fetched = fetcher.fetch(feedUrl, MAX_FEED_BYTES);
            FeedResult result = parseCachedFeed(fetched.body(), from, to, zone, index,
                    MAX_OCCURRENCES_PER_FEED);
            if (index == 0 && !result.title().isBlank()) title = result.title();
            int room = MAX_OCCURRENCES_PER_RESPONSE - events.size();
            if (room <= 0) {
                truncated = true;
                break;
            }
            if (result.events().size() > room) {
                events.addAll(result.events().subList(0, room));
                truncated = true;
            } else {
                events.addAll(result.events());
            }
            truncated |= result.truncated();
            if (events.size() >= MAX_OCCURRENCES_PER_RESPONSE) {
                if (index < feeds.size() - 1 || result.truncated()) truncated = true;
                break;
            }
        }

        events.sort(Comparator.comparing((CalendarEvent event) -> sortInstant(event, zone))
                .thenComparingInt(CalendarEvent::feedIndex));
        return new EventsResponse(clean(title, 256), events, Instant.now().toString(), 60, truncated);
    }

    FeedResult parseCachedFeed(String content, LocalDate from, LocalDate to, ZoneId zone,
                               int feedIndex, int limit) {
        if (content == null) {
            throw upstreamFailure();
        }
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        if (body.length == 0 || body.length > MAX_FEED_BYTES) {
            throw upstreamFailure();
        }

        ParsedFeedKey key = new ParsedFeedKey(contentDigest(body), from, to, zone, feedIndex, limit);
        CachedParsedFeed cached = parsedFeedCache.get(key, ignored -> {
            if (!parsePermits.tryAcquire()) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Calendar processing capacity is busy.");
            }
            try {
                FeedResult result = parseFeed(content, from, to, zone, feedIndex, limit);
                return new CachedParsedFeed(result, parsedFeedWeight(body.length, result));
            } finally {
                parsePermits.release();
            }
        });
        return cached.result();
    }

    FeedResult parseFeed(String content, LocalDate from, LocalDate to, ZoneId zone,
                         int feedIndex, int limit) {
        validateSourceShape(content);
        net.fortuna.ical4j.model.Calendar calendar;
        try {
            calendar = new CalendarBuilder().build(new StringReader(content));
        } catch (IOException | ParserException | RuntimeException exception) {
            throw upstreamFailure();
        }

        List<VEvent> components = calendar.getComponents(Component.VEVENT);
        if (components.size() > MAX_COMPONENTS) throw upstreamFailure();
        String title = calendar.getProperty("X-WR-CALNAME")
                .map(Property::getValue)
                .orElse("");

        Map<String, List<VEvent>> grouped = new LinkedHashMap<>();
        int anonymous = 0;
        for (VEvent event : components) {
            String uid = event.getUid().map(Uid::getValue).filter(value -> !value.isBlank())
                    .orElse("anonymous-" + anonymous++);
            grouped.computeIfAbsent(uid, ignored -> new ArrayList<>()).add(event);
        }

        List<CalendarEvent> output = new ArrayList<>();
        boolean truncated = false;
        for (Map.Entry<String, List<VEvent>> entry : grouped.entrySet()) {
            if (output.size() >= limit) {
                truncated = true;
                break;
            }
            List<VEvent> groupEvents = entry.getValue();
            VEvent master = groupEvents.stream()
                    .filter(event -> event.getProperty(Property.RECURRENCE_ID).isEmpty())
                    .findFirst()
                    .orElse(groupEvents.getFirst());
            DateProperty<?> startProperty = master.<DateProperty<?>>getProperty(Property.DTSTART).orElse(null);
            if (startProperty == null) continue;

            if (isUnsafeRecurrence(groupEvents)) {
                truncated = true;
                addStandalone(groupEvents, output, feedIndex, zone, from, to, limit);
                continue;
            }

        List<Period<Temporal>> periods = new ArrayList<>();
        try {
            Period<Temporal> range = rangePeriod(startProperty.getDate(), from, to, zone);
            periods.addAll(master.calculateRecurrenceSet(range));
            periods.replaceAll(CalendarFeedService::normalizeAllDayPeriod);
        } catch (RuntimeException exception) {
                truncated = true;
                addStandalone(groupEvents, output, feedIndex, zone, from, to, limit);
                continue;
            }

            Map<String, VEvent> overrides = new LinkedHashMap<>();
            for (VEvent candidate : groupEvents) {
                RecurrenceId<?> recurrenceId = candidate.<RecurrenceId<?>>getProperty(Property.RECURRENCE_ID).orElse(null);
                if (recurrenceId == null) continue;
                String recurrenceKey = temporalKey(recurrenceId.getDate());
                periods.removeIf(period -> temporalKey(period.getStart()).equals(recurrenceKey));
                if (isCancelled(candidate)) continue;

                DateProperty<?> overrideStart = candidate.<DateProperty<?>>getProperty(Property.DTSTART).orElse(null);
                if (overrideStart == null) continue;
                Period<Temporal> overridePeriod = standalonePeriod(candidate, overrideStart.getDate());
                Period<Temporal> range = rangePeriod(startProperty.getDate(), from, to, zone);
                if (overridePeriod != null && overridePeriod.intersects(range)) {
                    periods.add(overridePeriod);
                    overrides.put(temporalKey(overridePeriod.getStart()), candidate);
                }
            }

            int groupCount = 0;
            for (Period<Temporal> period : periods) {
                String startKey = temporalKey(period.getStart());
                VEvent chosen = overrides.getOrDefault(startKey, master);
                if (isCancelled(chosen)) continue;
                CalendarEvent item = toEvent(chosen, period, entry.getKey(), feedIndex, zone);
                if (item != null) output.add(item);
                groupCount++;
                if (groupCount >= MAX_OCCURRENCES_PER_GROUP || output.size() >= limit) {
                    truncated = true;
                    break;
                }
            }
            if (output.size() >= limit) {
                truncated = true;
                break;
            }
        }
        return new FeedResult(title, List.copyOf(output), truncated);
    }

    private static void validateSourceShape(String content) {
        if (content == null || content.isEmpty() || content.getBytes(StandardCharsets.UTF_8).length > MAX_FEED_BYTES) {
            throw upstreamFailure();
        }
        int lineLength = 0;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (current == '\r' || current == '\n') lineLength = 0;
            else if (++lineLength > 16_384) throw upstreamFailure();
        }
        Matcher matcher = EVENT_START.matcher(content);
        int componentCount = 0;
        while (matcher.find()) {
            if (++componentCount > MAX_COMPONENTS) throw upstreamFailure();
        }
    }

    private static boolean isUnsafeRecurrence(List<VEvent> groupEvents) {
        int recurrenceText = 0;
        int rules = 0;
        for (VEvent event : groupEvents) {
            for (Property property : event.getProperties(Property.RRULE)) {
                rules++;
                String value = property.getValue();
                recurrenceText += value.length();
                String upper = value.toUpperCase(java.util.Locale.ROOT);
                if (upper.contains("FREQ=SECONDLY") || upper.contains("FREQ=MINUTELY")
                        || UNSAFE_RECURRENCE_PART.matcher(value).find()) return true;
            }
            for (String name : List.of(Property.RDATE, Property.EXDATE, Property.EXRULE)) {
                for (Property property : event.getProperties(name)) recurrenceText += property.getValue().length();
            }
        }
        return rules > 1 || recurrenceText > MAX_RECURRENCE_TEXT_CHARS;
    }

    private static void addStandalone(List<VEvent> groupEvents, List<CalendarEvent> output,
                                      int feedIndex, ZoneId zone, LocalDate from, LocalDate to, int limit) {
        for (VEvent event : groupEvents) {
            if (output.size() >= limit || isCancelled(event)) return;
            DateProperty<?> startProperty = event.<DateProperty<?>>getProperty(Property.DTSTART).orElse(null);
            if (startProperty == null) continue;
            Temporal start = startProperty.getDate();
            Period<Temporal> range = rangePeriod(start, from, to, zone);
            Period<Temporal> eventPeriod = standalonePeriod(event, start);
            if (eventPeriod == null) continue;
            if (!eventPeriod.intersects(range)) continue;
            CalendarEvent item = toEvent(event, eventPeriod,
                    event.getUid().map(Uid::getValue).orElse("anonymous"), feedIndex, zone);
            if (item != null) output.add(item);
        }
    }

    private static Period<Temporal> standalonePeriod(VEvent event, Temporal start) {
        DateProperty<?> endProperty = event.<DateProperty<?>>getProperty(Property.DTEND).orElse(null);
        Temporal end = endProperty == null ? fallbackEnd(event, start) : endProperty.getDate();
        try {
            return normalizeAllDayPeriod(new Period<>(start, end));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Period<Temporal> normalizeAllDayPeriod(Period<Temporal> period) {
        Temporal start = period.getStart();
        Temporal end = period.getEnd();
        if (start instanceof LocalDate startDate
                && (!(end instanceof LocalDate endDate) || !endDate.isAfter(startDate))) {
            return new Period<>(startDate, startDate.plusDays(1));
        }
        return period;
    }

    private static Temporal fallbackEnd(VEvent event, Temporal start) {
        String durationText = event.getProperty(Property.DURATION).map(Property::getValue).orElse("");
        if (!durationText.isBlank()) {
            try {
                Dur duration = new Dur(durationText);
                if (duration.isNegative()) return start;
                int days = duration.getWeeks() * 7 + duration.getDays();
                Temporal end = days == 0 ? start : start.plus(java.time.Period.ofDays(days));
                Duration clock = Duration.ofHours(duration.getHours())
                        .plusMinutes(duration.getMinutes()).plusSeconds(duration.getSeconds());
                return clock.isZero() ? end : end.plus(clock);
            } catch (RuntimeException ignored) {
                return start;
            }
        }
        return start instanceof LocalDate ? start.plus(1, ChronoUnit.DAYS) : start;
    }

    private static CalendarEvent toEvent(VEvent event, Period<Temporal> period, String uid,
                                         int feedIndex, ZoneId zone) {
        Temporal start = period.getStart();
        Temporal end = period.getEnd();
        if (start == null || end == null) return null;
        boolean allDay = start instanceof LocalDate;
        String startText;
        String endText;
        if (allDay && end instanceof LocalDate) {
            startText = start.toString();
            endText = end.toString();
        } else {
            startText = instant(start, zone).toString();
            endText = instant(end, zone).toString();
        }
        String title = event.getProperty(Property.SUMMARY).map(Property::getValue).orElse("Untitled event");
        String location = event.getProperty(Property.LOCATION).map(Property::getValue).orElse("");
        String description = event.getProperty(Property.DESCRIPTION).map(Property::getValue).orElse("");
        String url = event.getProperty(Property.URL).map(Property::getValue).map(CalendarFeedService::safeEventUrl).orElse("");
        String id = eventId(feedIndex, uid, temporalKey(start));
        return new CalendarEvent(id, clean(title, 256), startText, endText, allDay,
                clean(location, 512), clean(description, 2048), url, feedIndex);
    }

    private static Instant instant(Temporal value, ZoneId zone) {
        if (value instanceof ZonedDateTime zoned) return zoned.toInstant();
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Instant instant) return instant;
        if (value instanceof LocalDateTime localDateTime) return localDateTime.atZone(zone).toInstant();
        if (value instanceof LocalDate localDate) return localDate.atStartOfDay(zone).toInstant();
        throw upstreamFailure();
    }

    private static Period<Temporal> rangePeriod(Temporal start, LocalDate from, LocalDate to, ZoneId zone) {
        Temporal rangeStart;
        Temporal rangeEnd;
        if (start instanceof LocalDate) {
            rangeStart = from;
            rangeEnd = to;
        } else if (start instanceof ZonedDateTime eventTime) {
            rangeStart = from.atStartOfDay(zone).withZoneSameInstant(eventTime.getZone());
            rangeEnd = to.atStartOfDay(zone).withZoneSameInstant(eventTime.getZone());
        } else if (start instanceof OffsetDateTime eventTime) {
            rangeStart = OffsetDateTime.ofInstant(from.atStartOfDay(zone).toInstant(), eventTime.getOffset());
            rangeEnd = OffsetDateTime.ofInstant(to.atStartOfDay(zone).toInstant(), eventTime.getOffset());
        } else if (start instanceof Instant) {
            rangeStart = from.atStartOfDay(zone).toInstant();
            rangeEnd = to.atStartOfDay(zone).toInstant();
        } else {
            rangeStart = from.atStartOfDay();
            rangeEnd = to.atStartOfDay();
        }
        return new Period<>(rangeStart, rangeEnd);
    }

    private static String temporalKey(Temporal temporal) {
        if (temporal instanceof LocalDate date) return "D:" + date;
        if (temporal instanceof ZonedDateTime value) return "T:" + value.toInstant();
        if (temporal instanceof OffsetDateTime value) return "T:" + value.toInstant();
        if (temporal instanceof Instant value) return "T:" + value;
        if (temporal instanceof LocalDateTime value) return "F:" + value;
        return temporal == null ? "" : temporal.toString();
    }

    private static String safeEventUrl(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null) {
                return clean(uri.toASCIIString(), 2048);
            }
        } catch (RuntimeException ignored) {
            // Invalid or active-content URLs are omitted from the API response.
        }
        return "";
    }

    private static boolean isCancelled(VEvent event) {
        return event.getProperty(Property.STATUS).map(Property::getValue)
                .map(value -> "CANCELLED".equalsIgnoreCase(value)).orElse(false);
    }

    private static Instant sortInstant(CalendarEvent event, ZoneId zone) {
        if (event.allDay()) return LocalDate.parse(event.start()).atStartOfDay(zone).toInstant();
        return Instant.parse(event.start());
    }

    private static String contentDigest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int parsedFeedWeight(int bodyBytes, FeedResult result) {
        long textLength = result.title().length();
        for (CalendarEvent event : result.events()) {
            textLength += event.id().length() + event.title().length() + event.start().length()
                    + event.end().length() + event.location().length() + event.description().length()
                    + event.url().length();
        }
        long weight = bodyBytes + 2L * textLength + 256L * (result.events().size() + 1L);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, weight));
    }

    private static String eventId(int feedIndex, String uid, String occurrence) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((feedIndex + "\u0000" + uid + "\u0000" + occurrence).getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte value : digest) output.append(String.format("%02x", value));
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String clean(String value, int maxLength) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && result.length() < maxLength; i++) {
            char character = value.charAt(i);
            if (character == '\n' || character == '\t' || character >= 0x20) result.append(character);
        }
        return result.toString().strip();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException upstreamFailure() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "A calendar feed could not be read.");
    }

    private record ParsedFeedKey(String bodyHash, LocalDate from, LocalDate to, ZoneId zone,
                                 int feedIndex, int limit) {}

    private record CachedParsedFeed(FeedResult result, int weight) {}

    record FeedResult(String title, List<CalendarEvent> events, boolean truncated) {}
}
