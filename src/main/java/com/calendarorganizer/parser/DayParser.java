package com.calendarorganizer.parser;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DayParser {

    private static final String DAY_TOKEN = "(?:mon(?:day)?|tue(?:s|sday)?|wed(?:nesday)?|thu(?:r|rs|rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)";
    private static final Pattern DAY_RANGE_PATTERN = Pattern.compile("\\b(" + DAY_TOKEN + ")\\s*(?:-|–|to|through|thru)\\s*(" + DAY_TOKEN + ")\\b");
    private static final Pattern SINGLE_DAY_PATTERN = Pattern.compile("\\b(" + DAY_TOKEN + ")\\b");
    private static final Pattern MWF_PATTERN = Pattern.compile("\\bm\\s*/\\s*w\\s*/\\s*f\\b");
    private static final Pattern TR_PATTERN = Pattern.compile("\\bt\\s*/\\s*th\\b");
    private static final Map<String, DayOfWeek> DAY_MAP = buildDayMap();

    public Set<DayOfWeek> parseRecurringDays(String lowerText) {
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (lowerText == null || lowerText.isBlank()) {
            return days;
        }
        if (lowerText.contains("weekdays")) {
            days.add(DayOfWeek.MONDAY);
            days.add(DayOfWeek.TUESDAY);
            days.add(DayOfWeek.WEDNESDAY);
            days.add(DayOfWeek.THURSDAY);
            days.add(DayOfWeek.FRIDAY);
        }
        if (lowerText.contains("weekends")) {
            days.add(DayOfWeek.SATURDAY);
            days.add(DayOfWeek.SUNDAY);
        }
        if (lowerText.contains("every day") || lowerText.contains("everyday") || lowerText.contains("daily")) {
            days.addAll(EnumSet.allOf(DayOfWeek.class));
        }
        if (MWF_PATTERN.matcher(lowerText).find()) {
            days.add(DayOfWeek.MONDAY);
            days.add(DayOfWeek.WEDNESDAY);
            days.add(DayOfWeek.FRIDAY);
        }
        if (TR_PATTERN.matcher(lowerText).find()) {
            days.add(DayOfWeek.TUESDAY);
            days.add(DayOfWeek.THURSDAY);
        }

        Matcher rangeMatcher = DAY_RANGE_PATTERN.matcher(lowerText);
        while (rangeMatcher.find()) {
            DayOfWeek start = DAY_MAP.get(rangeMatcher.group(1));
            DayOfWeek end = DAY_MAP.get(rangeMatcher.group(2));
            if (start != null && end != null) {
                addRange(days, start, end);
            }
        }

        Matcher singleMatcher = SINGLE_DAY_PATTERN.matcher(lowerText);
        while (singleMatcher.find()) {
            DayOfWeek day = DAY_MAP.get(singleMatcher.group(1));
            if (day != null) {
                days.add(day);
            }
        }
        return days;
    }

    public LocalDate parseExplicitDate(String lowerText, LocalDate referenceDate) {
        if (lowerText.contains("tomorrow")) {
            return referenceDate.plusDays(1);
        }
        if (lowerText.contains("today")) {
            return referenceDate;
        }
        if (lowerText.contains(" on ") || lowerText.contains("this ") || lowerText.contains("next ")) {
            Matcher matcher = SINGLE_DAY_PATTERN.matcher(lowerText);
            if (matcher.find()) {
                DayOfWeek day = DAY_MAP.get(matcher.group(1));
                if (day != null) {
                    return referenceDate.with(TemporalAdjusters.nextOrSame(day));
                }
            }
        }
        return null;
    }

    private void addRange(Set<DayOfWeek> target, DayOfWeek start, DayOfWeek end) {
        DayOfWeek cursor = start;
        for (int i = 0; i < 7; i++) {
            target.add(cursor);
            if (cursor == end) {
                break;
            }
            cursor = cursor.plus(1);
        }
    }

    private static Map<String, DayOfWeek> buildDayMap() {
        Map<String, DayOfWeek> map = new HashMap<>();
        map.put("mon", DayOfWeek.MONDAY);
        map.put("monday", DayOfWeek.MONDAY);
        map.put("tue", DayOfWeek.TUESDAY);
        map.put("tues", DayOfWeek.TUESDAY);
        map.put("tuesday", DayOfWeek.TUESDAY);
        map.put("wed", DayOfWeek.WEDNESDAY);
        map.put("wednesday", DayOfWeek.WEDNESDAY);
        map.put("thu", DayOfWeek.THURSDAY);
        map.put("thur", DayOfWeek.THURSDAY);
        map.put("thurs", DayOfWeek.THURSDAY);
        map.put("thursday", DayOfWeek.THURSDAY);
        map.put("fri", DayOfWeek.FRIDAY);
        map.put("friday", DayOfWeek.FRIDAY);
        map.put("sat", DayOfWeek.SATURDAY);
        map.put("saturday", DayOfWeek.SATURDAY);
        map.put("sun", DayOfWeek.SUNDAY);
        map.put("sunday", DayOfWeek.SUNDAY);
        return map;
    }

    public boolean mentionsSingleWeekdayOnly(String lowerText) {
        String clean = lowerText.toLowerCase(Locale.ROOT);
        if (clean.contains("every") || clean.contains("weekdays") || clean.contains("weekends") || clean.contains("daily")) {
            return false;
        }
        if (clean.contains("today") || clean.contains("tomorrow")) {
            return true;
        }
        return clean.contains(" on ") || clean.contains("this ") || clean.contains("next ");
    }
}
