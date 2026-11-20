package com.calendarorganizer.llm;

import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.GoalType;
import com.calendarorganizer.parser.ParseResult;
import com.calendarorganizer.parser.ParsedEvent;
import com.calendarorganizer.parser.ParsedGoal;
import com.calendarorganizer.parser.ParsedRecurringRule;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LlmJsonParser {

    public ParseResult parse(String rawText, String providerName, LocalDate referenceDate) throws LlmException {
        if (rawText == null || rawText.isBlank()) {
            throw new LlmException(providerName + " returned empty response.");
        }
        String jsonText = stripCodeFences(rawText.trim());
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(jsonText);
            if (!parsed.isJsonObject()) {
                throw new LlmException(providerName + " response was not a JSON object.");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new LlmException(providerName + " returned invalid JSON.", e);
        }

        List<ParsedEvent> events = new ArrayList<>();
        List<ParsedRecurringRule> rules = new ArrayList<>();
        List<ParsedGoal> goals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        parseEvents(root, events, warnings, referenceDate);
        parseRules(root, rules, warnings);
        parseGoals(root, goals, warnings);
        LocalTime sleepTime = parseSleepTime(root, warnings);

        return new ParseResult(events, rules, goals, sleepTime, providerName, warnings);
    }

    private String stripCodeFences(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline > 0 && lastFence > firstNewline) {
            return text.substring(firstNewline + 1, lastFence).trim();
        }
        return text;
    }

    private void parseEvents(JsonObject root,
                             List<ParsedEvent> events,
                             List<String> warnings,
                             LocalDate referenceDate) {
        JsonArray arr = getArray(root, "events");
        if (arr == null) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String title = getString(obj, "title");
            if (title == null || title.isBlank()) {
                title = "Event";
            }
            LocalDate date = parseDate(getString(obj, "date"), referenceDate, warnings);
            LocalTime start = parseTime(getString(obj, "startTime"), warnings);
            LocalTime end = parseTime(getString(obj, "endTime"), warnings);
            if (start == null || end == null) {
                warnings.add("Skipped LLM event missing startTime/endTime: " + title);
                continue;
            }
            EventCategory cat = parseCategory(getString(obj, "category"));
            events.add(new ParsedEvent(title, date, start, end, cat));
        }
    }

    private void parseRules(JsonObject root, List<ParsedRecurringRule> rules, List<String> warnings) {
        JsonArray arr = getArray(root, "recurringRules");
        if (arr == null) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String title = getString(obj, "title");
            if (title == null || title.isBlank()) {
                title = "Recurring Event";
            }
            Set<DayOfWeek> days = parseDays(getArray(obj, "daysOfWeek"));
            LocalTime start = parseTime(getString(obj, "startTime"), warnings);
            LocalTime end = parseTime(getString(obj, "endTime"), warnings);
            if (days.isEmpty() || start == null || end == null) {
                warnings.add("Skipped invalid LLM recurring rule: " + title);
                continue;
            }
            EventCategory cat = parseCategory(getString(obj, "category"));
            rules.add(new ParsedRecurringRule(title, days, start, end, cat));
        }
    }

    private void parseGoals(JsonObject root, List<ParsedGoal> goals, List<String> warnings) {
        JsonArray arr = getArray(root, "goals");
        if (arr == null) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            GoalType type = parseGoalType(getString(obj, "type"));
            if (type == null) {
                warnings.add("Skipped LLM goal with unknown type.");
                continue;
            }
            String title = getString(obj, "title");
            if (title == null || title.isBlank()) {
                if (type == GoalType.WORKOUT) {
                    title = "Workout";
                } else {
                    title = "Study";
                }
            }
            int sessions = getInt(obj, "sessionsPerWeek", 3);
            int sessionMinutes = getInt(obj, "sessionMinutes", 60);
            int weeklyMinutes = getInt(obj, "weeklyMinutes", 0);
            goals.add(new ParsedGoal(type, title, sessions, sessionMinutes, weeklyMinutes));
        }
    }

    private LocalTime parseSleepTime(JsonObject root, List<String> warnings) {
        String raw = getString(root, "sleepTime");
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw)) {
            return null;
        }
        return parseTime(raw, warnings);
    }

    private Set<DayOfWeek> parseDays(JsonArray arr) {
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (arr == null) {
            return days;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonPrimitive()) {
                continue;
            }
            String raw = el.getAsString().trim().toUpperCase(Locale.ROOT);
            DayOfWeek parsed = mapDay(raw);
            if (parsed != null) {
                days.add(parsed);
            }
        }
        return days;
    }

    private DayOfWeek mapDay(String raw) {
        switch (raw) {
            case "MON":
            case "MONDAY":
                return DayOfWeek.MONDAY;
            case "TUE":
            case "TUESDAY":
                return DayOfWeek.TUESDAY;
            case "WED":
            case "WEDNESDAY":
                return DayOfWeek.WEDNESDAY;
            case "THU":
            case "THUR":
            case "THURS":
            case "THURSDAY":
                return DayOfWeek.THURSDAY;
            case "FRI":
            case "FRIDAY":
                return DayOfWeek.FRIDAY;
            case "SAT":
            case "SATURDAY":
                return DayOfWeek.SATURDAY;
            case "SUN":
            case "SUNDAY":
                return DayOfWeek.SUNDAY;
            default:
                return null;
        }
    }

    private EventCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return EventCategory.GENERAL;
        }
        try {
            return EventCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return EventCategory.GENERAL;
        }
    }

    private GoalType parseGoalType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return GoalType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private LocalDate parseDate(String raw, LocalDate fallback, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
            warnings.add("Invalid date from LLM '" + raw + "', used reference date.");
            return fallback;
        }
    }

    private LocalTime parseTime(String raw, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
            warnings.add("Invalid time from LLM: " + raw);
            return null;
        }
    }

    private JsonArray getArray(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            return obj.getAsJsonArray(key);
        }
        return null;
    }

    private String getString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        if (obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private int getInt(JsonObject obj, String key, int defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }
}
