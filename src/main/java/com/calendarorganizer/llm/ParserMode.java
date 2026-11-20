package com.calendarorganizer.llm;

public enum ParserMode {
    AUTO("Auto"),
    RULES("Rules Only"),
    OLLAMA("Ollama"),
    GEMINI("Gemini");

    private final String label;

    ParserMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
