package com.calendarorganizer.service;

import java.util.Collections;
import java.util.List;

public class IcsImportSummary {

    private final int importedEvents;
    private final int importedRules;
    private final List<String> warnings;

    public IcsImportSummary(int importedEvents, int importedRules, List<String> warnings) {
        this.importedEvents = importedEvents;
        this.importedRules = importedRules;
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public int getImportedEvents() {
        return importedEvents;
    }

    public int getImportedRules() {
        return importedRules;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
