package com.calendarorganizer.llm;

import java.time.LocalDate;

public class LlmPromptBuilder {

    public String buildPrompt(String userSentence, LocalDate referenceDate) {
        return "Convert the scheduling request into JSON only. No explanation.\n"
                + "Reference date: " + referenceDate + " (" + referenceDate.getDayOfWeek() + ").\n"
                + "Schedule concrete events and recurringRules directly into realistic non-late-night slots "
                + "(respecting any sleep, wake-up, work, study, or workout timing hints the user gives).\n"
                + "Output schema:\n"
                + "{\n"
                + "  \"events\": [{\"title\": \"string\", \"date\": \"YYYY-MM-DD\", \"startTime\": \"HH:mm\", \"endTime\": \"HH:mm\", \"category\": \"WORK|BUSY|WORKOUT|STUDY|GENERAL\"}],\n"
                + "  \"recurringRules\": [{\"title\": \"string\", \"daysOfWeek\": [\"MONDAY\", \"TUESDAY\"], \"startTime\": \"HH:mm\", \"endTime\": \"HH:mm\", \"category\": \"WORK|BUSY|WORKOUT|STUDY|GENERAL\"}],\n"
                + "  \"goals\": [],\n"
                + "  \"sleepTime\": null\n"
                + "}\n"
                + "Use 24-hour HH:mm times.\n"
                + "Input: " + userSentence;
    }
}
