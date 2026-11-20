package com.calendarorganizer.parser;

import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.GoalType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RuleParser {

    private static final Pattern SPLIT_ACTION_AND = Pattern.compile(
            "\\s+and\\s+(?=(?:i\\s|i'm\\s|im\\s|sleep\\b|bed\\b|busy\\b|work\\s+(?:mon|tue|wed|thu|fri|sat|sun|weekdays|weekends|daily|every|\\d)|shift\\b|meeting\\b|class\\b|lecture\\b))"
    );
    private static final Pattern FREQ_PATTERN = Pattern.compile("(\\d+)\\s*(?:times?|x)\\s*(?:a|per)?\\s*week\\b");
    private static final Pattern WEEKLY_HOURS_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:hours?|hrs?|hr)\\s*(?:a|per)\\s*week\\b");
    private static final Pattern FOR_DURATION_PATTERN = Pattern.compile("for\\s+(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|hr|minutes?|mins?|min)\\b");

    private final DayParser dayParser;
    private final TimeRangeParser timeRangeParser;

    public RuleParser() {
        this.dayParser = new DayParser();
        this.timeRangeParser = new TimeRangeParser();
    }

    public ParseResult parse(String input, LocalDate referenceDate) {
        List<ParsedEvent> events = new ArrayList<>();
        List<ParsedRecurringRule> rules = new ArrayList<>();
        List<ParsedGoal> goals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        LocalTime sleepTime = null;

        if (input == null || input.isBlank()) {
            warnings.add("Please enter a schedule sentence.");
            return new ParseResult(events, rules, goals, null, "rules", warnings);
        }

        List<String> clauses = splitClauses(input);
        for (String rawClause : clauses) {
            String lower = rawClause.toLowerCase(Locale.ROOT).trim();
            if (lower.isEmpty()) {
                continue;
            }
            if (isSleepClause(lower)) {
                LocalTime parsedSleep = timeRangeParser.parseSleepTime(lower);
                if (parsedSleep != null) {
                    sleepTime = parsedSleep;
                } else {
                    warnings.add("Could not understand sleep time in: " + rawClause);
                }
                continue;
            }

            TimeRangeParser.TimeRange range = timeRangeParser.parseTimeRange(lower);
            if (range != null) {
                parseTimedClause(lower, range, referenceDate, events, rules);
                continue;
            }

            boolean foundGoal = parseGoalClause(lower, goals);
            if (!foundGoal) {
                warnings.add("Unrecognized phrase: " + rawClause);
            }
        }

        return new ParseResult(events, rules, goals, sleepTime, "rules", warnings);
    }

    private List<String> splitClauses(String input) {
        //splits compound sentences without breaking paired goals like work out and study
        String[] primary = input.split("[,;.]+");
        List<String> clauses = new ArrayList<>();
        for (String part : primary) {
            String[] secondary = SPLIT_ACTION_AND.split(part.trim());
            for (String s : secondary) {
                String cleaned = s.trim();
                if (cleaned.startsWith("and ")) {
                    cleaned = cleaned.substring(4).trim();
                }
                if (!cleaned.isEmpty()) {
                    clauses.add(cleaned);
                }
            }
        }
        return clauses;
    }

    private boolean isSleepClause(String lower) {
        return lower.contains("sleep") || lower.contains("bedtime") || lower.contains("go to bed") || lower.contains("bed at");
    }

    private void parseTimedClause(String lower,
                                  TimeRangeParser.TimeRange range,
                                  LocalDate referenceDate,
                                  List<ParsedEvent> events,
                                  List<ParsedRecurringRule> rules) {
        EventCategory category = detectTimedCategory(lower);
        String title = defaultTitleForCategory(category, lower);
        Set<DayOfWeek> days = dayParser.parseRecurringDays(lower);

        if (!days.isEmpty() && !dayParser.mentionsSingleWeekdayOnly(lower)) {
            rules.add(new ParsedRecurringRule(title, days, range.getStart(), range.getEnd(), category));
            return;
        }

        LocalDate eventDate = dayParser.parseExplicitDate(lower, referenceDate);
        if (eventDate == null) {
            eventDate = referenceDate;
        }
        events.add(new ParsedEvent(title, eventDate, range.getStart(), range.getEnd(), category));
    }

    private boolean parseGoalClause(String lower, List<ParsedGoal> goals) {
        boolean added = false;
        boolean mentionsWorkout = lower.contains("work out") || lower.contains("workout") || lower.contains("gym") || lower.contains("exercise");
        boolean mentionsStudy = lower.contains("study");

        int customSessions = extractInt(FREQ_PATTERN, lower);
        int customWeeklyMinutes = extractWeeklyMinutes(lower);
        int customDurationMinutes = extractForDurationMinutes(lower);

        if (mentionsWorkout) {
            int sessions = 3;
            if (customSessions > 0) {
                sessions = customSessions;
            }
            int minutes = 60;
            if (customDurationMinutes > 0 && !mentionsStudy) {
                minutes = customDurationMinutes;
            }
            int weekly = 0;
            if (customWeeklyMinutes > 0 && !mentionsStudy) {
                weekly = customWeeklyMinutes;
            }
            goals.add(new ParsedGoal(GoalType.WORKOUT, "Workout", sessions, minutes, weekly));
            added = true;
        }

        if (mentionsStudy) {
            int sessions = 5;
            if (customSessions > 0 && !mentionsWorkout) {
                sessions = customSessions;
            }
            int minutes = 90;
            if (customDurationMinutes > 0) {
                minutes = customDurationMinutes;
            }
            int weekly = sessions * minutes;
            if (customWeeklyMinutes > 0) {
                weekly = customWeeklyMinutes;
                if (minutes > weekly) {
                    minutes = weekly;
                }
            }
            goals.add(new ParsedGoal(GoalType.STUDY, "Study", sessions, minutes, weekly));
            added = true;
        }
        return added;
    }

    private EventCategory detectTimedCategory(String lower) {
        if (lower.contains("work out") || lower.contains("workout") || lower.contains("gym") || lower.contains("exercise")) {
            return EventCategory.WORKOUT;
        }
        if (lower.contains("study")) {
            return EventCategory.STUDY;
        }
        if (lower.contains("work") || lower.contains("shift") || lower.contains("job")) {
            return EventCategory.WORK;
        }
        if (lower.contains("busy") || lower.contains("meeting") || lower.contains("appointment")
                || lower.contains("class") || lower.contains("lecture")) {
            return EventCategory.BUSY;
        }
        return EventCategory.GENERAL;
    }

    private String defaultTitleForCategory(EventCategory category, String lower) {
        switch (category) {
            case WORK:
                if (lower.contains("night shift")) {
                    return "Night Shift";
                }
                return "Work";
            case BUSY:
                if (lower.contains("meeting")) {
                    return "Meeting";
                }
                if (lower.contains("class")) {
                    return "Class";
                }
                if (lower.contains("lecture")) {
                    return "Lecture";
                }
                if (lower.contains("appointment")) {
                    return "Appointment";
                }
                return "Busy";
            case WORKOUT:
                return "Workout";
            case STUDY:
                return "Study";
            default:
                return "Event";
        }
    }

    private int extractInt(Pattern pattern, String lower) {
        Matcher m = pattern.matcher(lower);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private int extractWeeklyMinutes(String lower) {
        Matcher m = WEEKLY_HOURS_PATTERN.matcher(lower);
        if (m.find()) {
            double hours = Double.parseDouble(m.group(1));
            return (int) Math.round(hours * 60.0);
        }
        return 0;
    }

    private int extractForDurationMinutes(String lower) {
        Matcher m = FOR_DURATION_PATTERN.matcher(lower);
        if (m.find()) {
            double amount = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            if (unit.startsWith("h")) {
                return (int) Math.round(amount * 60.0);
            }
            return (int) Math.round(amount);
        }
        return 0;
    }
}
