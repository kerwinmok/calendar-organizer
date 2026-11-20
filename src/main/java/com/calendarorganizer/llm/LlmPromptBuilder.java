package com.calendarorganizer.llm;

import java.time.LocalDate;

public class LlmPromptBuilder {

    public String buildPrompt(String userSentence, LocalDate referenceDate) {
        return "Convert the scheduling request into JSON only. No explanation.\n"
                + "Reference date: " + referenceDate + " (" + referenceDate.getDayOfWeek() + ").\n"
                + "Output schema:\n"
                + "{\n"
                + "  \"events\": [{\"title\": \"string\", \"date\": \"YYYY-MM-DD\", \"startTime\": \"HH:mm\", \"endTime\": \"HH:mm\", \"category\": \"WORK|BUSY|WORKOUT|STUDY|GENERAL\"}],\n"
                + "  \"recurringRules\": [{\"title\": \"string\", \"daysOfWeek\": [\"MONDAY\", \"TUESDAY\"], \"startTime\": \"HH:mm\", \"endTime\": \"HH:mm\", \"category\": \"WORK|BUSY|WORKOUT|STUDY|GENERAL\"}],\n"
                + "  \"goals\": [{\"type\": \"WORKOUT|STUDY\", \"title\": \"string\", \"sessionsPerWeek\": 3, \"sessionMinutes\": 60, \"weeklyMinutes\": 0}],\n"
                + "  \"sleepTime\": \"HH:mm or null\"\n"
                + "}\n"
                + "Use 24-hour HH:mm times. If sleepTime is midnight use 00:00.\n"
                + "Input: " + userSentence;
    }
}
