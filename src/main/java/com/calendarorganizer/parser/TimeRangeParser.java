package com.calendarorganizer.parser;

import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeRangeParser {

    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "(?<![\\d.])(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*(?:-|–|to)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b"
    );
    private static final Pattern AT_FOR_PATTERN = Pattern.compile(
            "(?:at|from)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s+for\\s+(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|hr|minutes?|mins?|min|h|m)\\b"
    );
    private static final Pattern SLEEP_TIME_PATTERN = Pattern.compile(
            "(?:at|by|around)?\\s*(midnight|\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)"
    );

    public TimeRange parseTimeRange(String lowerText) {
        Matcher atFor = AT_FOR_PATTERN.matcher(lowerText);
        if (atFor.find()) {
            int hour = Integer.parseInt(atFor.group(1));
            int minute = parseMinute(atFor.group(2));
            String meridiem = atFor.group(3);
            LocalTime start = buildSingleClockTime(hour, minute, meridiem, false);
            if (start != null) {
                double amount = Double.parseDouble(atFor.group(4));
                String unit = atFor.group(5);
                int durationMinutes = toMinutes(amount, unit);
                if (durationMinutes > 0) {
                    return new TimeRange(start, start.plusMinutes(durationMinutes));
                }
            }
        }

        Matcher range = RANGE_PATTERN.matcher(lowerText);
        if (range.find()) {
            int h1 = Integer.parseInt(range.group(1));
            int m1 = parseMinute(range.group(2));
            String mer1 = range.group(3);
            int h2 = Integer.parseInt(range.group(4));
            int m2 = parseMinute(range.group(5));
            String mer2 = range.group(6);
            return resolveRange(h1, m1, mer1, h2, m2, mer2);
        }
        return null;
    }

    public LocalTime parseSleepTime(String lowerText) {
        Matcher m = SLEEP_TIME_PATTERN.matcher(lowerText);
        LocalTime lastFound = null;
        while (m.find()) {
            String token = m.group(1).trim();
            if ("midnight".equals(token)) {
                lastFound = LocalTime.MIDNIGHT;
                continue;
            }
            Matcher parts = Pattern.compile("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").matcher(token);
            if (parts.matches()) {
                int hour = Integer.parseInt(parts.group(1));
                int minute = parseMinute(parts.group(2));
                String meridiem = parts.group(3);
                LocalTime parsed = buildSingleClockTime(hour, minute, meridiem, true);
                if (parsed != null) {
                    lastFound = parsed;
                }
            }
        }
        return lastFound;
    }

    private TimeRange resolveRange(int h1, int m1, String mer1, int h2, int m2, String mer2) {
        if (m1 < 0 || m1 > 59 || m2 < 0 || m2 > 59 || h1 > 23 || h2 > 23) {
            return null;
        }
        int startHour = h1;
        int endHour = h2;

        if (mer1 != null) {
            startHour = applyMeridiem(h1, mer1);
        }
        if (mer2 != null) {
            endHour = applyMeridiem(h2, mer2);
        }

        //infers am/pm for shorthand ranges like 7-6pm or 9-5
        if (mer1 == null && mer2 != null && h1 <= 12) {
            if ("pm".equals(mer2)) {
                int h2Twelve = h2 % 12;
                if (h1 > h2Twelve && h1 != 12) {
                    startHour = h1;
                } else if (h1 < 12) {
                    startHour = h1 + 12;
                } else {
                    startHour = 12;
                }
            } else if ("am".equals(mer2)) {
                if (h1 == 12) {
                    startHour = 0;
                } else if (h1 > h2) {
                    startHour = h1 + 12;
                }
            }
        } else if (mer1 == null && mer2 == null && h1 <= 12 && h2 <= 12) {
            if (h1 > h2 && h2 <= 11) {
                endHour = h2 + 12;
            }
        }

        if (startHour < 0 || startHour > 23 || endHour < 0 || endHour > 23) {
            return null;
        }
        return new TimeRange(LocalTime.of(startHour, m1), LocalTime.of(endHour, m2));
    }

    private LocalTime buildSingleClockTime(int hour, int minute, String meridiem, boolean sleepContext) {
        if (minute < 0 || minute > 59 || hour < 0 || hour > 23) {
            return null;
        }
        if (meridiem != null) {
            int resolved = applyMeridiem(hour, meridiem);
            if (resolved < 0 || resolved > 23) {
                return null;
            }
            return LocalTime.of(resolved, minute);
        }
        if (sleepContext) {
            if (hour == 12) {
                return LocalTime.of(0, minute);
            }
            if (hour >= 8 && hour <= 11) {
                return LocalTime.of(hour + 12, minute);
            }
            if (hour >= 1 && hour <= 4) {
                return LocalTime.of(hour, minute);
            }
        }
        return LocalTime.of(hour, minute);
    }

    private int applyMeridiem(int hour, String meridiem) {
        if (hour < 1 || hour > 12) {
            return -1;
        }
        if ("am".equals(meridiem)) {
            if (hour == 12) {
                return 0;
            }
            return hour;
        }
        if (hour == 12) {
            return 12;
        }
        return hour + 12;
    }

    private int parseMinute(String minuteGroup) {
        if (minuteGroup == null || minuteGroup.isBlank()) {
            return 0;
        }
        return Integer.parseInt(minuteGroup);
    }

    private int toMinutes(double amount, String unit) {
        if (unit.startsWith("h")) {
            return (int) Math.round(amount * 60.0);
        }
        return (int) Math.round(amount);
    }

    public static class TimeRange {
        private final LocalTime start;
        private final LocalTime end;

        public TimeRange(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
        }

        public LocalTime getStart() {
            return start;
        }

        public LocalTime getEnd() {
            return end;
        }
    }
}
