package com.calendarorganizer.ics;

import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class IcsParser {

    private static final DateTimeFormatter BASIC_DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Map<String, String> WINDOWS_ZONES = buildWindowsZoneMap();

    private final ZoneId targetZone;

    public IcsParser() {
        this(ZoneId.systemDefault());
    }

    public IcsParser(ZoneId targetZone) {
        this.targetZone = targetZone;
    }

    public IcsParseResult parseFile(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return parseText(content);
    }

    public IcsParseResult parseText(String icsText) {
        List<CalendarEvent> events = new ArrayList<>();
        List<RecurringRule> rules = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (icsText == null || icsText.isBlank()) {
            warnings.add("Empty .ics input.");
            return new IcsParseResult(events, rules, warnings);
        }

        List<String> lines = unfoldLines(icsText);
        boolean inEvent = false;
        int nestedDepth = 0;
        RawEvent raw = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if ("BEGIN:VEVENT".equalsIgnoreCase(trimmed)) {
                inEvent = true;
                nestedDepth = 0;
                raw = new RawEvent();
                continue;
            }
            if ("END:VEVENT".equalsIgnoreCase(trimmed)) {
                if (inEvent && raw != null) {
                    processRawEvent(raw, events, rules, warnings);
                }
                inEvent = false;
                nestedDepth = 0;
                raw = null;
                continue;
            }
            if (!inEvent || raw == null) {
                continue;
            }
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("BEGIN:")) {
                nestedDepth++;
                continue;
            }
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("END:")) {
                if (nestedDepth > 0) {
                    nestedDepth--;
                }
                continue;
            }
            if (nestedDepth == 0) {
                readProperty(line, raw);
            }
        }

        if (events.isEmpty() && rules.isEmpty() && warnings.isEmpty()) {
            warnings.add("No VEVENT blocks found in .ics input.");
        }
        return new IcsParseResult(events, rules, warnings);
    }

    private List<String> unfoldLines(String text) {
        //unfolds rfc5545 wrapped lines starting with space or tab
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] rawLines = normalized.split("\n", -1);
        List<String> result = new ArrayList<>();
        for (String rawLine : rawLines) {
            if ((rawLine.startsWith(" ") || rawLine.startsWith("\t")) && !result.isEmpty()) {
                int lastIdx = result.size() - 1;
                result.set(lastIdx, result.get(lastIdx) + rawLine.substring(1));
            } else {
                result.add(rawLine);
            }
        }
        return result;
    }

    private void readProperty(String line, RawEvent raw) {
        int colonIdx = line.indexOf(':');
        if (colonIdx <= 0) {
            return;
        }
        String left = line.substring(0, colonIdx);
        String value = line.substring(colonIdx + 1);
        String[] parts = left.split(";");
        String propName = parts[0].trim().toUpperCase(Locale.ROOT);
        Map<String, String> params = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int eqIdx = parts[i].indexOf('=');
            if (eqIdx > 0) {
                String pKey = parts[i].substring(0, eqIdx).trim().toUpperCase(Locale.ROOT);
                String pVal = parts[i].substring(eqIdx + 1).trim();
                params.put(pKey, pVal);
            }
        }

        switch (propName) {
            case "UID":
                raw.uid = value.trim();
                break;
            case "SUMMARY":
                raw.summary = unescapeText(value);
                break;
            case "DESCRIPTION":
                raw.description = unescapeText(value);
                break;
            case "DTSTART":
                raw.dtStart = value.trim();
                raw.dtStartParams = params;
                break;
            case "DTEND":
                raw.dtEnd = value.trim();
                raw.dtEndParams = params;
                break;
            case "DURATION":
                raw.duration = value.trim();
                break;
            case "RRULE":
                raw.rrule = value.trim();
                break;
            default:
                break;
        }
    }

    private void processRawEvent(RawEvent raw,
                                 List<CalendarEvent> events,
                                 List<RecurringRule> rules,
                                 List<String> warnings) {
        String title = raw.summary;
        if (title == null || title.isBlank()) {
            title = "Imported Event";
        }
        if (raw.dtStart == null || raw.dtStart.isBlank()) {
            warnings.add("Skipped event without DTSTART: " + title);
            return;
        }

        ParsedDateTime startParsed = parseDateTimeValue(raw.dtStart, raw.dtStartParams);
        if (startParsed == null) {
            warnings.add("Skipped event with invalid DTSTART '" + raw.dtStart + "': " + title);
            return;
        }

        LocalDateTime start = startParsed.dateTime;
        LocalDateTime end = resolveEndTime(raw, startParsed, warnings, title);

        if (raw.rrule != null && !raw.rrule.isBlank()) {
            RecurringRule rule = tryBuildWeeklyRule(title, start, end, raw.rrule, warnings);
            if (rule != null) {
                rules.add(rule);
                return;
            }
        }

        CalendarEvent event = new CalendarEvent(
                0L,
                raw.uid,
                title,
                start,
                end,
                EventCategory.IMPORTED,
                EventSource.IMPORTED,
                raw.description
        );
        events.add(event);
    }

    private LocalDateTime resolveEndTime(RawEvent raw,
                                         ParsedDateTime startParsed,
                                         List<String> warnings,
                                         String title) {
        LocalDateTime start = startParsed.dateTime;
        if (raw.dtEnd != null && !raw.dtEnd.isBlank()) {
            ParsedDateTime endParsed = parseDateTimeValue(raw.dtEnd, raw.dtEndParams);
            if (endParsed != null && endParsed.dateTime.isAfter(start)) {
                return endParsed.dateTime;
            }
            warnings.add("Invalid DTEND for '" + title + "', using default duration.");
        }
        if (raw.duration != null && !raw.duration.isBlank()) {
            try {
                Duration dur = Duration.parse(raw.duration);
                if (!dur.isNegative() && !dur.isZero()) {
                    return start.plus(dur);
                }
            } catch (DateTimeParseException ignored) {
                warnings.add("Invalid DURATION for '" + title + "', using default duration.");
            }
        }
        if (startParsed.allDay) {
            return start.plusDays(1);
        }
        return start.plusHours(1);
    }

    private RecurringRule tryBuildWeeklyRule(String title,
                                             LocalDateTime start,
                                             LocalDateTime end,
                                             String rruleStr,
                                             List<String> warnings) {
        Map<String, String> tokens = parseRruleTokens(rruleStr);
        String freq = tokens.get("FREQ");
        if (!"WEEKLY".equalsIgnoreCase(freq)) {
            warnings.add("Unsupported RRULE frequency for '" + title + "', imported single occurrence.");
            return null;
        }

        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        String byDay = tokens.get("BYDAY");
        if (byDay != null && !byDay.isBlank()) {
            String[] dayCodes = byDay.split(",");
            for (String code : dayCodes) {
                DayOfWeek day = mapIcsDay(code.trim());
                if (day != null) {
                    days.add(day);
                }
            }
        }
        if (days.isEmpty()) {
            days.add(start.getDayOfWeek());
        }

        LocalDate untilDate = null;
        String untilRaw = tokens.get("UNTIL");
        if (untilRaw != null && !untilRaw.isBlank()) {
            ParsedDateTime untilParsed = parseDateTimeValue(untilRaw, Map.of());
            if (untilParsed != null) {
                untilDate = untilParsed.dateTime.toLocalDate();
            }
        }

        return new RecurringRule(
                0L,
                title,
                days,
                start.toLocalTime(),
                end.toLocalTime(),
                EventCategory.IMPORTED,
                start.toLocalDate(),
                untilDate
        );
    }

    private Map<String, String> parseRruleTokens(String rruleStr) {
        Map<String, String> map = new HashMap<>();
        String[] parts = rruleStr.split(";");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String k = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String v = part.substring(eq + 1).trim();
                map.put(k, v);
            }
        }
        return map;
    }

    private DayOfWeek mapIcsDay(String code) {
        String clean = code.toUpperCase(Locale.ROOT);
        if (clean.length() > 2) {
            clean = clean.substring(clean.length() - 2);
        }
        switch (clean) {
            case "MO":
                return DayOfWeek.MONDAY;
            case "TU":
                return DayOfWeek.TUESDAY;
            case "WE":
                return DayOfWeek.WEDNESDAY;
            case "TH":
                return DayOfWeek.THURSDAY;
            case "FR":
                return DayOfWeek.FRIDAY;
            case "SA":
                return DayOfWeek.SATURDAY;
            case "SU":
                return DayOfWeek.SUNDAY;
            default:
                return null;
        }
    }

    private ParsedDateTime parseDateTimeValue(String rawValue, Map<String, String> params) {
        String val = rawValue.trim();
        try {
            if (val.endsWith("Z") && val.length() == 16) {
                LocalDateTime utcDt = LocalDateTime.parse(val.substring(0, 15), BASIC_DT);
                ZonedDateTime zoned = utcDt.atZone(ZoneOffset.UTC).withZoneSameInstant(targetZone);
                return new ParsedDateTime(zoned.toLocalDateTime(), false);
            }
            if (val.length() == 15 && val.charAt(8) == 'T') {
                LocalDateTime localDt = LocalDateTime.parse(val, BASIC_DT);
                String tzid = params.get("TZID");
                if (tzid != null && !tzid.isBlank()) {
                    ZoneId sourceZone = resolveZone(tzid);
                    ZonedDateTime converted = localDt.atZone(sourceZone).withZoneSameInstant(targetZone);
                    return new ParsedDateTime(converted.toLocalDateTime(), false);
                }
                return new ParsedDateTime(localDt, false);
            }
            if (val.length() == 8) {
                LocalDate date = LocalDate.parse(val, BASIC_DATE);
                return new ParsedDateTime(date.atStartOfDay(), true);
            }
        } catch (DateTimeParseException ignored) {
            return null;
        }
        return null;
    }

    private ZoneId resolveZone(String tzid) {
        String clean = tzid.replace("\"", "").trim();
        String mapped = WINDOWS_ZONES.get(clean);
        if (mapped != null) {
            clean = mapped;
        }
        try {
            return ZoneId.of(clean);
        } catch (Exception ignored) {
            return targetZone;
        }
    }

    private String unescapeText(String raw) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == 'n' || next == 'N') {
                    out.append('\n');
                } else if (next == ',' || next == ';' || next == '\\') {
                    out.append(next);
                } else {
                    out.append(next);
                }
                i = i + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static Map<String, String> buildWindowsZoneMap() {
        Map<String, String> map = new HashMap<>();
        map.put("Mountain Standard Time", "America/Denver");
        map.put("Pacific Standard Time", "America/Los_Angeles");
        map.put("Eastern Standard Time", "America/New_York");
        map.put("Central Standard Time", "America/Chicago");
        map.put("UTC", "UTC");
        map.put("GMT Standard Time", "Europe/London");
        return map;
    }

    private static class RawEvent {
        String uid;
        String summary;
        String description;
        String dtStart;
        Map<String, String> dtStartParams = Map.of();
        String dtEnd;
        Map<String, String> dtEndParams = Map.of();
        String duration;
        String rrule;
    }

    private static class ParsedDateTime {
        final LocalDateTime dateTime;
        final boolean allDay;

        ParsedDateTime(LocalDateTime dateTime, boolean allDay) {
            this.dateTime = dateTime;
            this.allDay = allDay;
        }
    }
}
