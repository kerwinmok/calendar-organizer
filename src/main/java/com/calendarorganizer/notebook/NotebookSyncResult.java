package com.calendarorganizer.notebook;

import java.util.List;

public class NotebookSyncResult {

    private final String notebookName;
    private final String notebookDisplayName;
    private final int uploadedCount;
    private final int skippedCount;
    private final List<String> warnings;

    public NotebookSyncResult(
            String notebookName,
            String notebookDisplayName,
            int uploadedCount,
            int skippedCount,
            List<String> warnings
    ) {
        if (notebookName == null) {
            this.notebookName = "";
        } else {
            this.notebookName = notebookName;
        }
        if (notebookDisplayName == null) {
            this.notebookDisplayName = "";
        } else {
            this.notebookDisplayName = notebookDisplayName;
        }
        this.uploadedCount = uploadedCount;
        this.skippedCount = skippedCount;
        if (warnings == null) {
            this.warnings = List.of();
        } else {
            this.warnings = List.copyOf(warnings);
        }
    }

    public String getNotebookName() {
        return notebookName;
    }

    public String getNotebookDisplayName() {
        return notebookDisplayName;
    }

    public int getUploadedCount() {
        return uploadedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(notebookDisplayName)
                .append(": uploaded ")
                .append(uploadedCount)
                .append(", skipped ")
                .append(skippedCount)
                .append(" unchanged");
        if (!warnings.isEmpty()) {
            sb.append(" (").append(warnings.size()).append(" warning(s))");
        }
        return sb.toString();
    }
}
