package com.calendarorganizer.ui;

import com.calendarorganizer.llm.ParserMode;
import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.model.ScheduleGoal;
import com.calendarorganizer.model.SchedulePreferences;
import com.calendarorganizer.scheduler.ScheduleResult;
import com.calendarorganizer.service.AppExecutors;
import com.calendarorganizer.service.ApplySentenceResult;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.service.IcsImportSummary;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class CalendarTabView extends BorderPane {

    private static final DateTimeFormatter WEEK_RANGE_FMT = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final CalendarService service;
    private final WeekGridView weekGridView;
    private final ScrollPane inspectorScroll;
    private final AtomicLong refreshSeq = new AtomicLong(0L);

    private final TextField sleepField = new TextField();
    private final ListView<RecurringRule> rulesList = new ListView<>();
    private final ListView<ScheduleGoal> goalsList = new ListView<>();
    private final Label selectedTitleLabel = new Label("None selected");
    private final Label selectedTimeLabel = new Label("");
    private final Label selectedMetaLabel = new Label("");
    private final Button editEventButton = new Button("Edit Event");
    private final Button deleteEventButton = new Button("Delete Event");

    private LocalDate currentWeekStart;
    private CalendarEvent selectedEvent;
    private UiTheme activeTheme = UiTheme.CHARCOAL;
    private boolean inspectorVisible = true;
    private String lastStatus = "Ready.";
    private Consumer<String> onStatusChanged;
    private Runnable onDataChanged;

    public CalendarTabView(CalendarService service) {
        this.service = service;
        this.currentWeekStart = service.weekStartOf(LocalDate.now());
        this.weekGridView = new WeekGridView();
        this.weekGridView.setOnEventSelected(this::onEventClicked);
        this.weekGridView.setOnEventEditRequested(this::handleEditEventOrRule);
        this.weekGridView.setOnEventTimesChanged(this::handleDragUpdateEvent);
        this.weekGridView.setOnSlotCreateRequested(this::openNewEventDialog);
        this.inspectorScroll = buildInspector();

        setCenter(weekGridView);
        setRight(inspectorScroll);

        refreshView();
    }

    private ScrollPane buildInspector() {
        sleepField.setPrefWidth(78);
        Button setSleepBtn = new Button("Set");
        setSleepBtn.setOnAction(e -> handleUpdateSleep());
        HBox sleepRow = new HBox(8, sleepField, setSleepBtn);
        sleepRow.setAlignment(Pos.CENTER_LEFT);
        TitledPane sleepPane = new TitledPane("Sleep Window", sleepRow);
        sleepPane.setExpanded(true);

        rulesList.setPrefHeight(130);
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
        rulesList.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                RecurringRule rule = rulesList.getSelectionModel().getSelectedItem();
                if (rule != null) {
                    openEditRuleDialog(rule);
                }
            }
        });
        Button editRuleBtn = new Button("Edit Rule");
        editRuleBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(editRuleBtn, Priority.ALWAYS);
        editRuleBtn.setOnAction(e -> {
            RecurringRule rule = rulesList.getSelectionModel().getSelectedItem();
            if (rule != null) {
                openEditRuleDialog(rule);
            }
        });
        Button deleteRuleBtn = new Button("Delete Rule");
        deleteRuleBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(deleteRuleBtn, Priority.ALWAYS);
        deleteRuleBtn.setOnAction(e -> {
            RecurringRule rule = rulesList.getSelectionModel().getSelectedItem();
            if (rule != null) {
                service.deleteRecurringRule(rule.getId());
                selectedEvent = null;
                refreshView();
                publishStatus("Deleted rule: " + rule.getTitle());
            }
        });
        HBox ruleActions = new HBox(6, editRuleBtn, deleteRuleBtn);
        VBox rulesBox = new VBox(6, rulesList, ruleActions);
        TitledPane rulesPane = new TitledPane("Recurring Rules", rulesBox);
        rulesPane.setExpanded(true);

        goalsList.setPrefHeight(115);
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
        deleteGoalBtn.setMaxWidth(Double.MAX_VALUE);
        deleteGoalBtn.setOnAction(e -> {
            ScheduleGoal goal = goalsList.getSelectionModel().getSelectedItem();
            if (goal != null) {
                handleDeleteGoal(goal);
            }
        });
        VBox goalsBox = new VBox(6, goalsList, deleteGoalBtn);
        TitledPane goalsPane = new TitledPane("Goals", goalsBox);
        goalsPane.setExpanded(true);

        selectedTitleLabel.getStyleClass().add("section-heading");
        selectedTitleLabel.setWrapText(true);
        selectedTimeLabel.setWrapText(true);
        selectedMetaLabel.getStyleClass().add("dim-label");
        selectedMetaLabel.setWrapText(true);
        editEventButton.setDisable(true);
        editEventButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(editEventButton, Priority.ALWAYS);
        editEventButton.setOnAction(e -> {
            if (selectedEvent != null) {
                handleEditEventOrRule(selectedEvent);
            }
        });
        deleteEventButton.setDisable(true);
        deleteEventButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(deleteEventButton, Priority.ALWAYS);
        deleteEventButton.setOnAction(e -> {
            if (selectedEvent != null && selectedEvent.getId() > 0) {
                service.deleteEvent(selectedEvent.getId());
                selectedEvent = null;
                refreshView();
                publishStatus("Deleted selected event.");
            }
        });
        HBox selectedActions = new HBox(6, editEventButton, deleteEventButton);
        VBox selectedBox = new VBox(6, selectedTitleLabel, selectedTimeLabel, selectedMetaLabel, selectedActions);
        TitledPane selectedPane = new TitledPane("Selected Event", selectedBox);
        selectedPane.setExpanded(true);

        VBox sections = new VBox(0, sleepPane, rulesPane, goalsPane, selectedPane);
        sections.setFillWidth(true);
        VBox.setVgrow(sections, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefWidth(270);
        scroll.setMinWidth(240);
        scroll.getStyleClass().add("lc-sidebar");
        return scroll;
    }

    public void setOnStatusChanged(Consumer<String> listener) {
        this.onStatusChanged = listener;
    }

    public void setOnDataChanged(Runnable listener) {
        this.onDataChanged = listener;
    }

    public void setActiveTheme(UiTheme theme) {
        this.activeTheme = theme;
    }

    public void openNewEventDialogDefault() {
        LocalDate date = LocalDate.now();
        if (date.isBefore(currentWeekStart) || date.isAfter(currentWeekStart.plusDays(6))) {
            date = currentWeekStart;
        }
        openNewEventDialog(date, LocalTime.of(9, 0), LocalTime.of(10, 0));
    }

    public void openNewEventDialog(LocalDate date, LocalTime start, LocalTime end) {
        Optional<CalendarEvent> created = EventDialog.showEventDialog(
                getScene().getWindow(), activeTheme, null, date, start, end
        );
        if (created.isPresent()) {
            CalendarEvent saved = service.saveManualEvent(created.get());
            selectedEvent = saved;
            refreshView();
            publishStatus("Added event: " + saved.getTitle() + " (" + saved.getStartTime().format(TIME_FMT)
                    + "-" + saved.getEndTime().format(TIME_FMT) + ")");
        }
    }

    private void handleEditEventOrRule(CalendarEvent event) {
        if (event.getSource() == EventSource.RECURRING) {
            RecurringRule matchingRule = findRuleForOccurrence(event);
            if (matchingRule != null) {
                openEditRuleDialog(matchingRule);
                return;
            }
        }
        if (event.getId() <= 0) {
            return;
        }
        Optional<CalendarEvent> updated = EventDialog.showEventDialog(
                getScene().getWindow(),
                activeTheme,
                event,
                event.getStartTime().toLocalDate(),
                event.getStartTime().toLocalTime(),
                event.getEndTime().toLocalTime()
        );
        if (updated.isPresent()) {
            CalendarEvent saved = updated.get();
            service.updateEvent(saved);
            selectedEvent = saved;
            refreshView();
            publishStatus("Updated event: " + saved.getTitle());
        }
    }

    private void openEditRuleDialog(RecurringRule rule) {
        Optional<RecurringRule> updated = EventDialog.showRecurringRuleDialog(
                getScene().getWindow(), activeTheme, rule
        );
        if (updated.isPresent()) {
            RecurringRule saved = updated.get();
            service.updateRecurringRule(saved);
            selectedEvent = null;
            refreshView();
            publishStatus("Updated recurring rule: " + saved.getTitle());
        }
    }

    private void handleDragUpdateEvent(CalendarEvent updated) {
        service.updateEvent(updated);
        selectedEvent = updated;
        refreshView();
        publishStatus("Moved " + updated.getTitle() + " to "
                + updated.getStartTime().format(TIME_FMT) + "-" + updated.getEndTime().format(TIME_FMT));
    }

    private RecurringRule findRuleForOccurrence(CalendarEvent occurrence) {
        String uid = occurrence.getUid();
        if (uid != null && uid.startsWith("rule-")) {
            int secondDash = uid.indexOf('-', 5);
            if (secondDash > 5) {
                try {
                    long ruleId = Long.parseLong(uid.substring(5, secondDash));
                    for (RecurringRule rule : service.findAllRecurringRules()) {
                        if (rule.getId() == ruleId) {
                            return rule;
                        }
                    }
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public void toggleInspector() {
        inspectorVisible = !inspectorVisible;
        if (inspectorVisible) {
            setRight(inspectorScroll);
        } else {
            setRight(null);
        }
    }

    public void submitSentence(String text, ParserMode mode, Consumer<Boolean> onFinished) {
        if (text == null || text.isBlank()) {
            publishStatus("Enter a schedule sentence first.");
            if (onFinished != null) {
                onFinished.accept(false);
            }
            return;
        }

        LocalDate refDate = LocalDate.now();
        if (refDate.isBefore(currentWeekStart) || refDate.isAfter(currentWeekStart.plusDays(6))) {
            refDate = currentWeekStart;
        }
        LocalDate effectiveRef = refDate;
        publishStatus("Parsing sentence...");

        Task<ApplySentenceResult> task = new Task<>() {
            @Override
            protected ApplySentenceResult call() {
                return service.applySentence(text, effectiveRef, mode);
            }
        };

        task.setOnSucceeded(ev -> {
            ApplySentenceResult res = task.getValue();
            if (selectedEvent != null && selectedEvent.getSource() == EventSource.SCHEDULED) {
                selectedEvent = null;
            }
            refreshView();
            publishStatus(formatApplySummary(res));
            if (onFinished != null) {
                onFinished.accept(true);
            }
        });

        task.setOnFailed(ev -> {
            Throwable err = task.getException();
            if (err != null) {
                publishStatus("Error: " + err.getMessage());
            } else {
                publishStatus("Failed to parse sentence.");
            }
            if (onFinished != null) {
                onFinished.accept(false);
            }
        });

        CalendarService.backgroundExecutor().execute(task);
    }

    public void rescheduleCurrentWeek() {
        LocalDate targetWeek = currentWeekStart;
        publishStatus("Scheduling week...");
        Task<ScheduleResult> task = new Task<>() {
            @Override
            protected ScheduleResult call() {
                return service.rescheduleWeek(targetWeek);
            }
        };
        task.setOnSucceeded(ev -> {
            ScheduleResult result = task.getValue();
            if (selectedEvent != null && selectedEvent.getSource() == EventSource.SCHEDULED) {
                selectedEvent = null;
            }
            refreshView();
            String msg = "Scheduled " + result.getScheduledEvents().size() + " goal sessions.";
            if (!result.getUnscheduledNotes().isEmpty()) {
                msg = msg + " " + String.join(" ", result.getUnscheduledNotes());
            }
            publishStatus(msg);
        });
        task.setOnFailed(ev -> publishStatus("Scheduling failed."));
        CalendarService.backgroundExecutor().execute(task);
    }

    public void importIcsFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import .ics Calendar");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("iCalendar files (*.ics)", "*.ics"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        publishStatus("Importing " + file.getName() + "...");
        Task<IcsImportSummary> task = new Task<>() {
            @Override
            protected IcsImportSummary call() throws Exception {
                return service.importIcsFile(file.toPath());
            }
        };
        task.setOnSucceeded(ev -> {
            refreshView();
            publishStatus(formatImportSummary(task.getValue()));
        });
        task.setOnFailed(ev -> {
            Throwable err = task.getException();
            if (err != null) {
                publishStatus("Import failed: " + err.getMessage());
            } else {
                publishStatus("Import failed.");
            }
        });
        CalendarService.backgroundExecutor().execute(task);
    }

    public void pasteIcsText() {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Paste .ics Text");
        dialog.setHeaderText("Paste raw iCalendar (.ics) content below:");
        WindowsTitleBar.styleDialog(dialog, activeTheme);

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
            String rawText = pasted.get();
            publishStatus("Importing pasted .ics...");
            Task<IcsImportSummary> task = new Task<>() {
                @Override
                protected IcsImportSummary call() {
                    return service.importIcsText(rawText);
                }
            };
            task.setOnSucceeded(ev -> {
                refreshView();
                publishStatus(formatImportSummary(task.getValue()));
            });
            task.setOnFailed(ev -> publishStatus("Import failed."));
            CalendarService.backgroundExecutor().execute(task);
        }
    }

    public void exportIcsFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Calendar to .ics");
        chooser.setInitialFileName("calendar-organizer.ics");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("iCalendar files (*.ics)", "*.ics"));
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        publishStatus("Exporting .ics...");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                service.exportIcsFile(file.toPath());
                return null;
            }
        };
        task.setOnSucceeded(ev -> publishStatus("Exported calendar to " + file.getAbsolutePath()));
        task.setOnFailed(ev -> {
            Throwable err = task.getException();
            if (err != null) {
                publishStatus("Export failed: " + err.getMessage());
            } else {
                publishStatus("Export failed.");
            }
        });
        CalendarService.backgroundExecutor().execute(task);
    }

    public void shiftWeek(int deltaWeeks) {
        currentWeekStart = currentWeekStart.plusWeeks(deltaWeeks);
        refreshView();
    }

    public void goToday() {
        currentWeekStart = service.weekStartOf(LocalDate.now());
        refreshView();
    }

    public LocalDate getCurrentWeekStart() {
        return currentWeekStart;
    }

    public String getWeekRangeLabel() {
        LocalDate weekEnd = currentWeekStart.plusDays(6);
        return currentWeekStart.format(WEEK_RANGE_FMT) + " – " + weekEnd.format(WEEK_RANGE_FMT) + ", " + weekEnd.getYear();
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void refreshView() {
        LocalDate targetWeek = currentWeekStart;
        long seq = refreshSeq.incrementAndGet();
        AppExecutors.ioExecutor().execute(() -> {
            List<CalendarEvent> events = service.getEventsForWeek(targetWeek);
            List<RecurringRule> rules = service.findAllRecurringRules();
            List<ScheduleGoal> goals = service.findAllGoals();
            SchedulePreferences prefs = service.loadPreferences();
            Platform.runLater(() -> {
                if (refreshSeq.get() != seq) {
                    return;
                }
                weekGridView.setWeekData(targetWeek, events, selectedEvent);
                rulesList.getItems().setAll(rules);
                goalsList.getItems().setAll(goals);
                sleepField.setText(prefs.getSleepTime().format(TIME_FMT));

                updateSelectedPanel();
                if (onDataChanged != null) {
                    onDataChanged.run();
                }
            });
        });
    }

    private void handleDeleteGoal(ScheduleGoal goal) {
        LocalDate targetWeek = currentWeekStart;
        publishStatus("Updating goals and rescheduling...");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                service.deleteGoal(goal.getId());
                service.rescheduleWeek(targetWeek);
                return null;
            }
        };
        task.setOnSucceeded(ev -> {
            selectedEvent = null;
            refreshView();
            publishStatus("Deleted goal: " + goal.getTitle());
        });
        task.setOnFailed(ev -> publishStatus("Failed to delete goal."));
        CalendarService.backgroundExecutor().execute(task);
    }

    private void handleUpdateSleep() {
        String raw = sleepField.getText();
        if (raw == null || raw.isBlank()) {
            return;
        }
        LocalTime parsed;
        try {
            parsed = EventFormValidator.parseTime(raw);
        } catch (IllegalArgumentException ex) {
            publishStatus("Invalid sleep time. Use HH:mm (for example 00:00 or 23:00).");
            return;
        }
        LocalDate targetWeek = currentWeekStart;
        publishStatus("Updating sleep window...");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                service.updateSleepTime(parsed);
                if (!service.findAllGoals().isEmpty()) {
                    service.rescheduleWeek(targetWeek);
                }
                return null;
            }
        };
        task.setOnSucceeded(ev -> {
            if (selectedEvent != null && selectedEvent.getSource() == EventSource.SCHEDULED) {
                selectedEvent = null;
            }
            refreshView();
            publishStatus("Sleep time updated to " + parsed.format(TIME_FMT));
        });
        task.setOnFailed(ev -> publishStatus("Failed to update sleep time."));
        CalendarService.backgroundExecutor().execute(task);
    }

    private void onEventClicked(CalendarEvent event) {
        selectedEvent = event;
        weekGridView.setSelectedEvent(event);
        updateSelectedPanel();
    }

    public void clearSelection() {
        selectedEvent = null;
        weekGridView.setSelectedEvent(null);
        updateSelectedPanel();
    }

    private void updateSelectedPanel() {
        if (selectedEvent == null) {
            selectedTitleLabel.setText("None selected");
            selectedTimeLabel.setText("");
            selectedMetaLabel.setText("");
            editEventButton.setDisable(true);
            deleteEventButton.setDisable(true);
            return;
        }
        selectedTitleLabel.setText(selectedEvent.getTitle());
        selectedTimeLabel.setText(selectedEvent.getStartTime().toLocalDate() + " "
                + selectedEvent.getStartTime().format(TIME_FMT) + " -> "
                + selectedEvent.getEndTime().format(TIME_FMT));
        if (selectedEvent.getSource() == EventSource.RECURRING) {
            selectedMetaLabel.setText("Recurring occurrence (Edit Rule or delete via Recurring Rules)");
            editEventButton.setDisable(false);
            deleteEventButton.setDisable(true);
        } else {
            selectedMetaLabel.setText(selectedEvent.getCategory().name() + " / " + selectedEvent.getSource().name());
            boolean hasId = selectedEvent.getId() > 0;
            editEventButton.setDisable(!hasId);
            deleteEventButton.setDisable(!hasId);
        }
    }

    private void publishStatus(String status) {
        this.lastStatus = status;
        if (onStatusChanged != null) {
            onStatusChanged.accept(status);
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
            sb.append(" · Scheduled ").append(res.getScheduleResult().getScheduledEvents().size()).append(" blocks");
            if (!res.getScheduleResult().getUnscheduledNotes().isEmpty()) {
                sb.append(" (").append(String.join(" ", res.getScheduleResult().getUnscheduledNotes())).append(")");
            }
        }
        if (!res.getParseResult().getWarnings().isEmpty()) {
            sb.append(" · ").append(String.join("; ", res.getParseResult().getWarnings()));
        }
        return sb.toString();
    }

    private String formatImportSummary(IcsImportSummary summary) {
        String text = "Imported " + summary.getImportedEvents() + " events and " + summary.getImportedRules() + " rules.";
        if (!summary.getWarnings().isEmpty()) {
            text = text + " Warnings: " + String.join("; ", summary.getWarnings());
        }
        return text;
    }
}
