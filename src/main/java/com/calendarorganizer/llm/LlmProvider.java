package com.calendarorganizer.llm;

import com.calendarorganizer.parser.ParseResult;

import java.time.LocalDate;

public interface LlmProvider {

    String getName();

    boolean isAvailable();

    ParseResult parse(String input, LocalDate referenceDate) throws LlmException;
}
