package com.calendarorganizer.ui;

import com.calendarorganizer.llm.ParserMode;
import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.model.ScheduleGoal;
import com.calendarorganizer.model.SchedulePreferences;
import com.calendarorganizer.scheduler.ScheduleResult;
import com.calendarorganizer.service.ApplySentenceResult;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.service.IcsImportSummary;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

public class CalendarTabView extends BorderPane {

    private static final DateTimeFormatter WEEK_RANGE_FMT = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final CalendarService service;
    private final WeekGridView weekGridView;

    private final TextField sentenceField = new TextField();
    private final ComboBox<ParserMode> parserModeBox = new ComboBox<>();
    private final Button addButton = new Button("Add");
    private final Label weekLabel = new Label();
    private final Label statusLabel = new Label("Ready.");

    private final TextField sleepField = new TextField();
    private final ListView<RecurringRule> rulesList = new ListView<>();
    private final ListView<ScheduleGoal> goalsList = new ListView<>();
    private final Label selectedTitleLabel = new Label("None selected");
    private final Label selectedTimeLabel = new Label("");
    private final Label selectedMetaLabel = new Label("");
    private final Button deleteEventButton = new Button("Delete Event");

    private LocalDate currentWeekStart;
    private CalendarEvent selectedEvent;

    public CalendarTabView(CalendarService service) {
        this.service = service;
        this.currentWeekStart = service.weekStartOf(LocalDate.now());
        this.weekGridView = new WeekGridView();
        this.weekGridView.setOnEventSelected(this::onEventClicked);

        setTop(buildTopToolbar());
        setCenter(weekGridView);
        setRight(buildSidebar());

        refreshView();
    }

    private VBox buildTopToolbar() {
        sentenceField.setPromptText("e.g. \"i work mon-thurs 7-6pm\" or \"i want to work out and study, i'm busy at 8pm for 2 hours, sleep at 12\"");
        HBox.setHgrow(sentenceField, Priority.ALWAYS);
        sentenceField.setOnAction(e -> handleAddSentence());

        parserModeBox.getItems().setAll(ParserMode.values());
        parserModeBox.setValue(ParserMode.AUTO);

        addButton.setOnAction(e -> handleAddSentence());
        Button scheduleBtn = new Button("Auto-Schedule");
        scheduleBtn.setOnAction(e -> handleReschedule());

        HBox row1 = new HBox(8, sentenceField, parserModeBox, addButton, scheduleBtn);
        row1.setAlignment(Pos.CENTER_LEFT);

        Button prevBtn = new Button("< Prev");
        prevBtn.setOnAction(e -> shiftWeek(-1));
        Button todayBtn = new Button("Today");
        todayBtn.setOnAction(e -> {
            currentWeekStart = service.weekStartOf(LocalDate.now());
            refreshView();
        });
        Button nextBtn = new Button("Next >");
        nextBtn.setOnAction(e -> shiftWeek(1));

        weekLabel.getStyleClass().add("section-heading");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button importFileBtn = new Button("Import .ics File");
        importFileBtn.setOnAction(e -> handleImportFile());
        Button pasteIcsBtn = new Button("Paste .ics");
        pasteIcsBtn.setOnAction(e -> handlePasteIcs());
        Button exportBtn = new Button("Export .ics");
        exportBtn.setOnAction(e -> handleExportIcs());
        Button clearBtn = new Button("Clear Scheduled");
        clearBtn.setOnAction(e -> handleClearScheduled());

        HBox row2 = new HBox(8, prevBtn, todayBtn, nextBtn, weekLabel, spacer,
                importFileBtn, pasteIcsBtn, exportBtn, clearBtn);
        row2.setAlignment(Pos.CENTER_LEFT);

        statusLabel.getStyleClass().add("status-label");
        statusLabel.setWrapText(true);

        VBox box = new VBox(8, row1, row2, statusLabel);
        box.setPadding(new Insets(10, 12, 10, 12));
        box.getStyleClass().add("toolbar-bar");
        return box;
    }

    private VBox buildSidebar() {
        Label sleepHeading = new Label("Sleep Time");
        sleepHeading.getStyleClass().add("section-heading");
        sleepField.setPrefWidth(80);
        Button setSleepBtn = new Button("Set");
        setSleepBtn.setOnAction(e -> handleUpdateSleep());
        HBox sleepRow = new HBox(8, sleepField, setSleepBtn);
        sleepRow.setAlignment(Pos.CENTER_LEFT);

        Label rulesHeading = new Label("Recurring Rules");
        rulesHeading.getStyleClass().add("section-heading");
        rulesList.setPrefHeight(150);
        rulesList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(RecurringRule item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getTitle() + " (" + formatDays(item) + " "
                            + item.getStartTime().format(TIME_FMT) + "-" + item.getEndTime().format(TIME_FMT) + ")");
                }
            }
        });
        Button deleteRuleBtn = new Button("Delete Rule");
        deleteRuleBtn.setOnAction(e -> {
            RecurringRule rule = rulesList.getSelectionModel().getSelectedItem();
            if (rule != null) {
                service.deleteRecurringRule(rule.getId());
                refreshView();
                statusLabel.setText("Deleted recurring rule: " + rule.getTitle());
            }
        });

        Label goalsHeading = new Label("Goals");
        goalsHeading.getStyleClass().add("section-heading");
        goalsList.setPrefHeight(130);
        goalsList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ScheduleGoal item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getTitle() + " (" + item.getSessionsPerWeek() + "x "
                            + item.getSessionMinutes() + "m, total " + item.totalTargetMinutes() + "m)");
                }
            }
        });
        Button deleteGoalBtn = new Button("Delete Goal");
        deleteGoalBtn.setOnAction(e -> {
            ScheduleGoal goal = goalsList.getSelectionModel().getSelectedItem();
            if (goal != null) {
                service.deleteGoal(goal.getId());
                service.rescheduleWeek(currentWeekStart);
                refreshView();
                statusLabel.setText("Deleted goal: " + goal.getTitle());
            }
        });

        Label selectedHeading = new Label("Selected Event");
        selectedHeading.getStyleClass().add("section-heading");
        selectedTitleLabel.setWrapText(true);
        selectedTimeLabel.setWrapText(true);
        selectedMetaLabel.setWrapText(true);
        deleteEventButton.setDisable(true);
        deleteEventButton.setOnAction(e -> {
            if (selectedEvent != null && selectedEvent.getId() > 0) {
                service.deleteEvent(selectedEvent.getId());
                selectedEvent = null;
                refreshView();
                statusLabel.setText("Deleted selected event.");
            }
        });

        VBox sidebar = new VBox(8,
                sleepHeading, sleepRow,
                new Separator(),
                rulesHeading, rulesList, deleteRuleBtn,
                new Separator(),
                goalsHeading, goalsList, deleteGoalBtn,
                new Separator(),
                selectedHeading, selectedTitleLabel, selectedTimeLabel, selectedMetaLabel, deleteEventButton
        );
        sidebar.getStyleClass().add("sidebar-pane");
        sidebar.setPrefWidth(270);
        sidebar.setMinWidth(240);
        return sidebar;
    }

    private void handleAddSentence() {
        String text = sentenceField.getText();
        if (text == null || text.isBlank()) {
            statusLabel.setText("Enter a schedule sentence first.");
            return;
        }
        ParserMode mode = parserModeBox.getValue();
        LocalDate refDate = LocalDate.now();
        if (refDate.isBefore(currentWeekStart) || refDate.isAfter(currentWeekStart.plusDays(6))) {
            refDate = currentWeekStart;
        }
        LocalDate effectiveRef = refDate;

        addButton.setDisable(true);
        statusLabel.setText("Parsing sentence...");

        Task<ApplySentenceResult> task = new Task<>() {
            @Override
            protected ApplySentenceResult call() {
                return service.applySentence(text, effectiveRef, mode);
            }
        };

        task.setOnSucceeded(ev -> {
            addButton.setDisable(false);
            ApplySentenceResult res = task.getValue();
            sentenceField.clear();
            refreshView();
            statusLabel.setText(formatApplySummary(res));
        });

        task.setOnFailed(ev -> {
            addButton.setDisable(false);
            Throwable err = task.getException();
            if (err != null) {
                statusLabel.setText("Error: " + err.getMessage());
            } else {
                statusLabel.setText("Failed to parse sentence.");
            }
        });

        Thread worker = new Thread(task, "sentence-parser");
        worker.setDaemon(true);
        worker.start();
    }

    private void handleReschedule() {
        ScheduleResult result = service.rescheduleWeek(currentWeekStart);
        refreshView();
        String msg = "Scheduled " + result.getScheduledEvents().size() + " goal sessions.";
        if (!result.getUnscheduledNotes().isEmpty()) {
            msg = msg + " " + String.join(" ", result.getUnscheduledNotes());
        }
        statusLabel.setText(msg);
    }

    private void handleClearScheduled() {
        service.clearScheduledEvents();
        refreshView();
        statusLabel.setText("Cleared scheduled sessions.");
    }

    private void handleUpdateSleep() {
        String raw = sleepField.getText();
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            LocalTime parsed = LocalTime.parse(raw.trim(), TIME_FMT);
            service.updateSleepTime(parsed);
            if (!service.findAllGoals().isEmpty()) {
                service.rescheduleWeek(currentWeekStart);
            }
            refreshView();
            statusLabel.setText("Sleep time updated to " + parsed.format(TIME_FMT));
        } catch (DateTimeParseException ex) {
            statusLabel.setText("Invalid sleep time. Use HH:mm, for example 00:00 or 23:00.");
        }
    }

    private void handleImportFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import .ics Calendar");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("iCalendar files (*.ics)", "*.ics"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            IcsImportSummary summary = service.importIcsFile(file.toPath());
            refreshView();
            statusLabel.setText(formatImportSummary(summary));
        } catch (IOException ex) {
            statusLabel.setText("Import failed: " + ex.getMessage());
        }
    }

    private void handlePasteIcs() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Paste .ics Text");
        dialog.setHeaderText("Paste raw iCalendar (.ics) content below:");
        TextArea area = new TextArea();
        area.setPrefRowCount(14);
        area.setPrefColumnCount(56);
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return area.getText();
            }
            return null;
        });

        Optional<String> pasted = dialog.showAndWait();
        if (pasted.isPresent()) {
            IcsImportSummary summary = service.importIcsText(pasted.get());
            refreshView();
            statusLabel.setText(formatImportSummary(summary));
        }
    }

    private void handleExportIcs() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Calendar to .ics");
        chooser.setInitialFileName("calendar-organizer.ics");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("iCalendar files (*.ics)", "*.ics"));
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            service.exportIcsFile(file.toPath());
            statusLabel.setText("Exported calendar to " + file.getAbsolutePath());
        } catch (IOException ex) {
            statusLabel.setText("Export failed: " + ex.getMessage());
        }
    }

    private void shiftWeek(int deltaWeeks) {
        currentWeekStart = currentWeekStart.plusWeeks(deltaWeeks);
        refreshView();
    }

    private void onEventClicked(CalendarEvent event) {
        selectedEvent = event;
        refreshView();
    }

    public void refreshView() {
        LocalDate weekEnd = currentWeekStart.plusDays(6);
        weekLabel.setText(currentWeekStart.format(WEEK_RANGE_FMT) + " - " + weekEnd.format(WEEK_RANGE_FMT) + ", " + weekEnd.getYear());

        List<CalendarEvent> events = service.getEventsForWeek(currentWeekStart);
        weekGridView.setWeekData(currentWeekStart, events, selectedEvent);

        rulesList.getItems().setAll(service.findAllRecurringRules());
        goalsList.getItems().setAll(service.findAllGoals());
        SchedulePreferences prefs = service.loadPreferences();
        sleepField.setText(prefs.getSleepTime().format(TIME_FMT));

        updateSelectedPanel();
    }

    private void updateSelectedPanel() {
        if (selectedEvent == null) {
            selectedTitleLabel.setText("None selected");
            selectedTimeLabel.setText("");
            selectedMetaLabel.setText("");
            deleteEventButton.setDisable(true);
            return;
        }
        selectedTitleLabel.setText(selectedEvent.getTitle());
        selectedTimeLabel.setText(selectedEvent.getStartTime().toLocalDate() + " "
                + selectedEvent.getStartTime().format(TIME_FMT) + " -> "
                + selectedEvent.getEndTime().format(TIME_FMT));
        if (selectedEvent.getSource() == EventSource.RECURRING) {
            selectedMetaLabel.setText("RECURRING occurrence (delete via Recurring Rules above)");
            deleteEventButton.setDisable(true);
        } else {
            selectedMetaLabel.setText(selectedEvent.getCategory().name() + " / " + selectedEvent.getSource().name());
            deleteEventButton.setDisable(selectedEvent.getId() <= 0);
        }
    }

    private String formatDays(RecurringRule rule) {
        List<DayOfWeek> sorted = new ArrayList<>(rule.getDaysOfWeek());
        sorted.sort(Comparator.comparingInt(DayOfWeek::getValue));
        StringJoiner joiner = new StringJoiner(",");
        for (DayOfWeek day : sorted) {
            joiner.add(day.name().substring(0, 3));
        }
        return joiner.toString();
    }

    private String formatApplySummary(ApplySentenceResult res) {
        StringBuilder sb = new StringBuilder();
        sb.append("Parsed via ").append(res.getParseResult().getParserUsed()).append(": ");
        sb.append(res.getAddedEvents()).append(" events, ");
        sb.append(res.getAddedRules()).append(" rules, ");
        sb.append(res.getUpdatedGoals()).append(" goals");
        if (res.isUpdatedSleep()) {
            sb.append(", sleep updated");
        }
        if (res.getScheduleResult() != null) {
            sb.append(". Scheduled ").append(res.getScheduleResult().getScheduledEvents().size()).append(" goal sessions.");
            if (!res.getScheduleResult().getUnscheduledNotes().isEmpty()) {
                sb.append(" ").append(String.join(" ", res.getScheduleResult().getUnscheduledNotes()));
            }
        }
        if (!res.getParseResult().getWarnings().isEmpty()) {
            sb.append(" Warnings: ").append(String.join("; ", res.getParseResult().getWarnings()));
        }
        return sb.toString();
    }

    private String formatImportSummary(IcsImportSummary summary) {
        String text = "Imported " + summary.getImportedEvents() + " events and " + summary.getImportedRules() + " recurring rules.";
        if (!summary.getWarnings().isEmpty()) {
            text = text + " Warnings: " + String.join("; ", summary.getWarnings());
        }
        return text;
    }
}
