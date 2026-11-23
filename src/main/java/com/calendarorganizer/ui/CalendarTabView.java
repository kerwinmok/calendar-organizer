package com.calendarorganizer.ui;

import com.calendarorganizer.llm.ParserMode;
import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.CalendarLayer;
import com.calendarorganizer.model.EventColor;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.model.RecurringRule;
import com.calendarorganizer.service.AppExecutors;
import com.calendarorganizer.service.ApplySentenceResult;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.service.IcsImportSummary;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final Label clusterHeaderLabel = new Label("");
    private final ListView<CalendarEvent> clusterEventsList = new ListView<>();
    private final Label selectedTitleLabel = new Label("None selected");
    private final Label selectedTimeLabel = new Label("");
    private final Label selectedMetaLabel = new Label("");
    private final TextArea selectedNotesArea = new TextArea();
    private final Button editEventButton = new Button("Edit");
    private final Button deleteEventButton = new Button("Delete");

    private final ListView<CalendarLayer> calendarsList = new ListView<>();
    private final Button addCalendarBtn = new Button("+ Calendar");
    private final Button editCalendarBtn = new Button("Edit");
    private final Button onlyCalendarBtn = new Button("Only");
    private final Button allCalendarsBtn = new Button("All");
    private final Button deleteCalendarBtn = new Button("Delete");

    private LocalDate currentWeekStart;
    private CalendarEvent selectedEvent;
    private List<CalendarEvent> selectedClusterEvents = List.of();
    private List<CalendarLayer> currentCalendars = List.of();
    private List<RecurringRule> cachedRules = List.of();
    private Map<Long, EventColor> calendarColorMap = Map.of();
    private Map<Long, String> calendarNameMap = Map.of();
    private boolean updatingClusterList;
    private boolean updatingCalendarsList;
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
        this.weekGridView.setOnEventGroupSelected(this::onEventGroupClicked);
        this.weekGridView.setOnEventEditRequested(this::handleEditEventOrRule);
        this.weekGridView.setOnEventTimesChanged(this::handleDragUpdateEvent);
        this.weekGridView.setOnSlotCreateRequested(this::openNewEventDialog);
        this.inspectorScroll = buildInspector();

        setCenter(weekGridView);
        setRight(inspectorScroll);

        refreshView();
    }

    private ScrollPane buildInspector() {
        clusterHeaderLabel.getStyleClass().add("caution-label");
        clusterHeaderLabel.setVisible(false);
        clusterHeaderLabel.setManaged(false);

        clusterEventsList.getStyleClass().add("cluster-events-list");
        clusterEventsList.setPrefHeight(135);
        clusterEventsList.setVisible(false);
        clusterEventsList.setManaged(false);
        clusterEventsList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(CalendarEvent item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getStartTime().format(TIME_FMT)
                            + "-" + item.getEndTime().format(TIME_FMT)
                            + "  " + item.getTitle());
                }
            }
        });
        clusterEventsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (updatingClusterList || newVal == null) {
                return;
            }
            selectedEvent = newVal;
            weekGridView.setSelectedEvent(newVal);
            updateSelectedPanel();
        });
        clusterEventsList.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                CalendarEvent chosen = clusterEventsList.getSelectionModel().getSelectedItem();
                if (chosen != null) {
                    handleEditEventOrRule(chosen);
                }
            }
        });

        selectedTitleLabel.getStyleClass().add("section-heading");
        selectedTitleLabel.setWrapText(true);
        selectedTimeLabel.setWrapText(true);
        selectedMetaLabel.getStyleClass().add("dim-label");
        selectedMetaLabel.setWrapText(true);

        selectedNotesArea.getStyleClass().add("event-notes-viewer");
        selectedNotesArea.setEditable(false);
        selectedNotesArea.setWrapText(true);
        selectedNotesArea.setPrefRowCount(5);
        selectedNotesArea.setPromptText("No description");
        selectedNotesArea.setDisable(true);

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
        deleteEventButton.setOnAction(e -> handleDeleteSelectedEvent());
        HBox selectedActions = new HBox(6, editEventButton, deleteEventButton);

        VBox selectedBox = new VBox(
                6,
                clusterHeaderLabel,
                clusterEventsList,
                selectedTitleLabel,
                selectedTimeLabel,
                selectedMetaLabel,
                selectedNotesArea,
                selectedActions
        );
        TitledPane selectedPane = new TitledPane("Selected Event", selectedBox);
        selectedPane.setExpanded(true);

        calendarsList.setPrefHeight(140);
        calendarsList.setCellFactory(list -> new ListCell<>() {
            private final CheckBox visibleCheck = new CheckBox();
            private final Region swatch = new Region();
            private final Label nameLabel = new Label();
            private final HBox row = new HBox(8, visibleCheck, swatch, nameLabel);
            private CalendarLayer boundLayer;

            {
                swatch.getStyleClass().add("calendar-color-dot");
                row.setAlignment(Pos.CENTER_LEFT);
                visibleCheck.setOnAction(e -> {
                    if (updatingCalendarsList || boundLayer == null) {
                        return;
                    }
                    long layerId = boundLayer.getId();
                    boolean nextVisible = visibleCheck.isSelected();
                    runCalendarMutation(null, () -> service.setCalendarVisible(layerId, nextVisible));
                });
            }

            @Override
            protected void updateItem(CalendarLayer item, boolean empty) {
                super.updateItem(item, empty);
                boundLayer = item;
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    boolean prevUpdating = updatingCalendarsList;
                    updatingCalendarsList = true;
                    try {
                        visibleCheck.setSelected(item.isVisible());
                    } finally {
                        updatingCalendarsList = prevUpdating;
                    }
                    swatch.setStyle("-fx-background-color: " + item.getColor().getSwatchHex() + ";");
                    nameLabel.setText(item.getName());
                    setGraphic(row);
                    setText(null);
                }
            }
        });
        calendarsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) ->
                updateCalendarActionButtons());

        addCalendarBtn.setOnAction(e -> handleCreateCalendarLayer());
        editCalendarBtn.setOnAction(e -> handleEditCalendarLayer());
        onlyCalendarBtn.setOnAction(e -> handleIsolateSelectedCalendar());
        allCalendarsBtn.setOnAction(e -> runCalendarMutation("Showing all calendars.", () -> service.setAllCalendarsVisible(true)));
        deleteCalendarBtn.setOnAction(e -> handleDeleteCalendarLayer());

        addCalendarBtn.setMaxWidth(Double.MAX_VALUE);
        editCalendarBtn.setMaxWidth(Double.MAX_VALUE);
        onlyCalendarBtn.setMaxWidth(Double.MAX_VALUE);
        allCalendarsBtn.setMaxWidth(Double.MAX_VALUE);
        deleteCalendarBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(addCalendarBtn, Priority.ALWAYS);
        HBox.setHgrow(editCalendarBtn, Priority.ALWAYS);
        HBox.setHgrow(onlyCalendarBtn, Priority.ALWAYS);
        HBox.setHgrow(allCalendarsBtn, Priority.ALWAYS);
        HBox.setHgrow(deleteCalendarBtn, Priority.ALWAYS);

        HBox calRowTop = new HBox(6, addCalendarBtn, editCalendarBtn, deleteCalendarBtn);
        HBox calRowBottom = new HBox(6, onlyCalendarBtn, allCalendarsBtn);
        VBox calendarsBox = new VBox(6, calendarsList, calRowTop, calRowBottom);
        TitledPane calendarsPane = new TitledPane("Calendars", calendarsBox);
        calendarsPane.setExpanded(true);

        VBox sections = new VBox(0, selectedPane, calendarsPane);
        sections.setFillWidth(true);
        VBox.setVgrow(sections, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(sections);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefWidth(275);
        scroll.setMinWidth(240);
        scroll.getStyleClass().add("lc-sidebar");
        updateCalendarActionButtons();
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
        long defaultCalId = resolvePreferredCalendarId();
        Optional<EventDialog.EventDialogResult> created = EventDialog.showUnifiedEventDialog(
                getScene().getWindow(),
                activeTheme,
                null,
                date,
                start,
                end,
                currentCalendars,
                defaultCalId
        );
        if (created.isPresent()) {
            EventDialog.EventDialogResult result = created.get();
            if (result.isRecurring()) {
                RecurringRule savedRule = service.saveRecurringRule(result.getRecurringRule());
                ensureCalendarVisibleIfHidden(savedRule.getCalendarId());
                refreshView();
                publishStatus("Added weekly event: " + savedRule.getTitle() + " (" + formatDays(savedRule) + ")");
            } else {
                CalendarEvent saved = service.saveManualEvent(result.getEvent());
                ensureCalendarVisibleIfHidden(saved.getCalendarId());
                selectedEvent = saved;
                refreshView();
                publishStatus("Added event: " + saved.getTitle() + " (" + saved.getStartTime().format(TIME_FMT)
                        + "-" + saved.getEndTime().format(TIME_FMT) + ")");
            }
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
        Optional<EventDialog.EventDialogResult> updated = EventDialog.showUnifiedEventDialog(
                getScene().getWindow(),
                activeTheme,
                event,
                event.getStartTime().toLocalDate(),
                event.getStartTime().toLocalTime(),
                event.getEndTime().toLocalTime(),
                currentCalendars,
                event.getCalendarId()
        );
        if (updated.isPresent() && !updated.get().isRecurring()) {
            CalendarEvent saved = updated.get().getEvent();
            service.updateEvent(saved);
            ensureCalendarVisibleIfHidden(saved.getCalendarId());
            selectedEvent = saved;
            refreshView();
            publishStatus("Updated event: " + saved.getTitle());
        }
    }

    private void handleDeleteSelectedEvent() {
        if (selectedEvent == null) {
            return;
        }
        if (selectedEvent.getSource() == EventSource.RECURRING) {
            RecurringRule matchingRule = findRuleForOccurrence(selectedEvent);
            if (matchingRule != null) {
                service.deleteRecurringRule(matchingRule.getId());
                selectedEvent = null;
                refreshView();
                publishStatus("Deleted weekly event: " + matchingRule.getTitle());
            }
            return;
        }
        if (selectedEvent.getId() > 0) {
            String title = selectedEvent.getTitle();
            service.deleteEvent(selectedEvent.getId());
            selectedEvent = null;
            refreshView();
            publishStatus("Deleted event: " + title);
        }
    }

    private void openEditRuleDialog(RecurringRule rule) {
        Optional<RecurringRule> updated = EventDialog.showRecurringRuleDialog(
                getScene().getWindow(), activeTheme, rule, currentCalendars
        );
        if (updated.isPresent()) {
            RecurringRule saved = updated.get();
            service.updateRecurringRule(saved);
            ensureCalendarVisibleIfHidden(saved.getCalendarId());
            selectedEvent = null;
            refreshView();
            publishStatus("Updated weekly event: " + saved.getTitle());
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
                    for (RecurringRule rule : cachedRules) {
                        if (rule.getId() == ruleId) {
                            return rule;
                        }
                    }
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
        long targetCalendarId = resolvePreferredCalendarId();
        publishStatus("Parsing schedule request...");

        Task<ApplySentenceResult> task = new Task<>() {
            @Override
            protected ApplySentenceResult call() {
                return service.applySentence(text, effectiveRef, mode, targetCalendarId);
            }
        };

        task.setOnSucceeded(ev -> {
            ApplySentenceResult res = task.getValue();
            ensureCalendarVisibleIfHidden(targetCalendarId);
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

    public void importIcsFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import .ics Calendar");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("iCalendar files (*.ics)", "*.ics"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        Optional<CalendarLayer> target = chooseTargetCalendarForImport("Import .ics into Calendar");
        if (target.isEmpty()) {
            return;
        }
        long targetId = target.get().getId();
        String targetName = target.get().getName();
        publishStatus("Importing " + file.getName() + " into " + targetName + "...");
        Task<IcsImportSummary> task = new Task<>() {
            @Override
            protected IcsImportSummary call() throws Exception {
                return service.importIcsFile(file.toPath(), targetId);
            }
        };
        task.setOnSucceeded(ev -> {
            ensureCalendarVisibleIfHidden(targetId);
            refreshView();
            publishStatus(formatImportSummary(task.getValue(), targetName));
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
            Optional<CalendarLayer> target = chooseTargetCalendarForImport("Import .ics into Calendar");
            if (target.isEmpty()) {
                return;
            }
            long targetId = target.get().getId();
            String targetName = target.get().getName();
            String rawText = pasted.get();
            publishStatus("Importing pasted .ics into " + targetName + "...");
            Task<IcsImportSummary> task = new Task<>() {
                @Override
                protected IcsImportSummary call() {
                    return service.importIcsText(rawText, targetId);
                }
            };
            task.setOnSucceeded(ev -> {
                ensureCalendarVisibleIfHidden(targetId);
                refreshView();
                publishStatus(formatImportSummary(task.getValue(), targetName));
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
            List<CalendarLayer> calendars = service.findAllCalendars();
            List<RecurringRule> rules = service.findAllRecurringRules();
            List<CalendarEvent> events = service.getVisibleEventsForWeek(targetWeek, calendars, rules);
            Map<Long, EventColor> colorMap = new HashMap<>();
            Map<Long, String> nameMap = new HashMap<>();
            for (CalendarLayer layer : calendars) {
                colorMap.put(layer.getId(), layer.getColor());
                nameMap.put(layer.getId(), layer.getName());
            }
            Platform.runLater(() -> {
                if (refreshSeq.get() != seq) {
                    return;
                }
                this.currentCalendars = calendars;
                this.cachedRules = rules;
                this.calendarColorMap = colorMap;
                this.calendarNameMap = nameMap;
                syncCalendarsList(calendars);

                reconcileSelectedCluster(events);
                weekGridView.setWeekData(targetWeek, events, selectedEvent, colorMap);

                updateSelectedPanel();
                if (onDataChanged != null) {
                    onDataChanged.run();
                }
            });
        });
    }

    private void syncCalendarsList(List<CalendarLayer> calendars) {
        CalendarLayer previouslySelected = calendarsList.getSelectionModel().getSelectedItem();
        long prevId = -1L;
        if (previouslySelected != null) {
            prevId = previouslySelected.getId();
        }
        updatingCalendarsList = true;
        try {
            calendarsList.getItems().setAll(calendars);
            CalendarLayer match = null;
            for (CalendarLayer layer : calendars) {
                if (layer.getId() == prevId) {
                    match = layer;
                    break;
                }
            }
            if (match != null) {
                calendarsList.getSelectionModel().select(match);
            } else if (!calendars.isEmpty()) {
                calendarsList.getSelectionModel().selectFirst();
            }
        } finally {
            updatingCalendarsList = false;
        }
        updateCalendarActionButtons();
    }

    private void updateCalendarActionButtons() {
        CalendarLayer sel = calendarsList.getSelectionModel().getSelectedItem();
        boolean hasSel = sel != null;
        boolean multiple = currentCalendars.size() > 1;
        editCalendarBtn.setDisable(!hasSel);
        onlyCalendarBtn.setDisable(!hasSel || !multiple);
        allCalendarsBtn.setDisable(!multiple);
        deleteCalendarBtn.setDisable(!hasSel || !multiple);
    }

    private long resolvePreferredCalendarId() {
        CalendarLayer selected = calendarsList.getSelectionModel().getSelectedItem();
        if (selected != null && selected.isVisible()) {
            return selected.getId();
        }
        for (CalendarLayer layer : currentCalendars) {
            if (layer.isVisible()) {
                return layer.getId();
            }
        }
        if (selected != null) {
            return selected.getId();
        }
        if (!currentCalendars.isEmpty()) {
            return currentCalendars.get(0).getId();
        }
        return 1L;
    }

    private void ensureCalendarVisibleIfHidden(long calendarId) {
        for (CalendarLayer layer : currentCalendars) {
            if (layer.getId() == calendarId && !layer.isVisible()) {
                AppExecutors.ioExecutor().execute(() -> {
                    service.setCalendarVisible(calendarId, true);
                    Platform.runLater(this::refreshView);
                });
                return;
            }
        }
    }

    private Optional<CalendarLayer> chooseTargetCalendarForImport(String headerTitle) {
        if (currentCalendars.isEmpty()) {
            return Optional.of(new CalendarLayer(1L, "General", EventColor.BLUE, true));
        }
        if (currentCalendars.size() == 1) {
            return Optional.of(currentCalendars.get(0));
        }
        long preferredId = resolvePreferredCalendarId();
        CalendarLayer defaultChoice = currentCalendars.get(0);
        for (CalendarLayer layer : currentCalendars) {
            if (layer.getId() == preferredId) {
                defaultChoice = layer;
                break;
            }
        }
        ChoiceDialog<CalendarLayer> dialog = new ChoiceDialog<>(defaultChoice, currentCalendars);
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle(headerTitle);
        dialog.setHeaderText("Select the target calendar layer");
        dialog.setContentText("Calendar:");
        WindowsTitleBar.styleDialog(dialog, activeTheme);
        return dialog.showAndWait();
    }

    private void handleCreateCalendarLayer() {
        Optional<CalendarLayer> created = EventDialog.showCalendarLayerDialog(
                getScene().getWindow(), activeTheme, null
        );
        if (created.isEmpty()) {
            return;
        }
        CalendarLayer draft = created.get();
        runCalendarMutation("Created calendar: " + draft.getName(), () ->
                service.createCalendar(draft.getName(), draft.getColor()));
    }

    private void handleEditCalendarLayer() {
        CalendarLayer selected = calendarsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Optional<CalendarLayer> updated = EventDialog.showCalendarLayerDialog(
                getScene().getWindow(), activeTheme, selected
        );
        if (updated.isEmpty()) {
            return;
        }
        CalendarLayer modified = updated.get();
        runCalendarMutation("Updated calendar: " + modified.getName(), () ->
                service.updateCalendar(modified));
    }

    private void handleIsolateSelectedCalendar() {
        CalendarLayer selected = calendarsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        runCalendarMutation("Viewing only: " + selected.getName(), () ->
                service.setOnlyCalendarVisible(selected.getId()));
    }

    private void handleDeleteCalendarLayer() {
        CalendarLayer selected = calendarsList.getSelectionModel().getSelectedItem();
        if (selected == null || currentCalendars.size() <= 1) {
            return;
        }
        CalendarLayer fallback = null;
        for (CalendarLayer candidate : currentCalendars) {
            if (candidate.getId() != selected.getId()) {
                fallback = candidate;
                break;
            }
        }
        if (fallback == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(getScene().getWindow());
        confirm.setTitle("Delete Calendar");
        confirm.setHeaderText("Delete calendar \"" + selected.getName() + "\"?");
        confirm.setContentText("Existing events in this calendar will move to \"" + fallback.getName() + "\".");
        WindowsTitleBar.styleDialog(confirm, activeTheme);
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        long deleteId = selected.getId();
        long fallbackId = fallback.getId();
        String msg = "Deleted calendar \"" + selected.getName() + "\" (events moved to " + fallback.getName() + ").";
        runCalendarMutation(msg, () -> service.deleteCalendar(deleteId, fallbackId));
    }

    private void runCalendarMutation(String successStatus, Runnable action) {
        AppExecutors.ioExecutor().execute(() -> {
            try {
                action.run();
                Platform.runLater(() -> {
                    refreshView();
                    if (successStatus != null && !successStatus.isBlank()) {
                        publishStatus(successStatus);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> publishStatus(ex.getMessage()));
            }
        });
    }

    private void onEventClicked(CalendarEvent event) {
        clearClusterSelection();
        selectedEvent = event;
        weekGridView.setSelectedEvent(event);
        updateSelectedPanel();
        inspectorScroll.setVvalue(0.0);
    }

    private void onEventGroupClicked(List<CalendarEvent> group) {
        if (group == null || group.isEmpty()) {
            return;
        }
        this.selectedClusterEvents = new ArrayList<>(group);
        CalendarEvent active = null;
        if (selectedEvent != null) {
            for (CalendarEvent candidate : selectedClusterEvents) {
                if (isSameOccurrence(selectedEvent, candidate)) {
                    active = candidate;
                    break;
                }
            }
        }
        if (active == null) {
            active = selectedClusterEvents.get(0);
        }
        this.selectedEvent = active;
        syncClusterListUi();
        weekGridView.setSelectedEvent(active);
        updateSelectedPanel();
        if (!inspectorVisible) {
            toggleInspector();
        }
        inspectorScroll.setVvalue(0.0);
    }

    public void clearSelection() {
        clearClusterSelection();
        selectedEvent = null;
        weekGridView.setSelectedEvent(null);
        updateSelectedPanel();
    }

    private void clearClusterSelection() {
        selectedClusterEvents = List.of();
        syncClusterListUi();
    }

    private void reconcileSelectedCluster(List<CalendarEvent> loadedEvents) {
        if (selectedClusterEvents.isEmpty()) {
            return;
        }
        List<CalendarEvent> refreshed = new ArrayList<>();
        for (CalendarEvent oldItem : selectedClusterEvents) {
            for (CalendarEvent candidate : loadedEvents) {
                if (isSameOccurrence(oldItem, candidate)) {
                    refreshed.add(candidate);
                    break;
                }
            }
        }
        refreshed.sort(Comparator.comparing(CalendarEvent::getStartTime).thenComparing(CalendarEvent::getEndTime));
        selectedClusterEvents = refreshed;

        if (selectedEvent != null) {
            CalendarEvent matchedSelected = null;
            for (CalendarEvent candidate : refreshed) {
                if (isSameOccurrence(selectedEvent, candidate)) {
                    matchedSelected = candidate;
                    break;
                }
            }
            if (matchedSelected != null) {
                selectedEvent = matchedSelected;
            } else if (!refreshed.isEmpty()) {
                selectedEvent = refreshed.get(0);
            } else {
                selectedEvent = null;
            }
        } else if (!refreshed.isEmpty()) {
            selectedEvent = refreshed.get(0);
        }

        if (selectedClusterEvents.size() <= 1) {
            selectedClusterEvents = List.of();
        }
        syncClusterListUi();
    }

    private void syncClusterListUi() {
        boolean showCluster = selectedClusterEvents.size() > 1;
        clusterHeaderLabel.setVisible(showCluster);
        clusterHeaderLabel.setManaged(showCluster);
        clusterEventsList.setVisible(showCluster);
        clusterEventsList.setManaged(showCluster);

        updatingClusterList = true;
        try {
            if (showCluster) {
                clusterHeaderLabel.setText(selectedClusterEvents.size() + " events in this slot");
                clusterEventsList.getItems().setAll(selectedClusterEvents);
                if (selectedEvent != null) {
                    clusterEventsList.getSelectionModel().select(selectedEvent);
                } else {
                    clusterEventsList.getSelectionModel().selectFirst();
                }
            } else {
                clusterHeaderLabel.setText("");
                clusterEventsList.getItems().clear();
            }
        } finally {
            updatingClusterList = false;
        }
    }

    private boolean isSameOccurrence(CalendarEvent a, CalendarEvent b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.getId() > 0 && b.getId() > 0) {
            return a.getId() == b.getId();
        }
        return Objects.equals(a.getUid(), b.getUid()) && Objects.equals(a.getStartTime(), b.getStartTime());
    }

    private void updateSelectedPanel() {
        if (selectedEvent == null) {
            selectedTitleLabel.setText("None selected");
            selectedTimeLabel.setText("");
            selectedMetaLabel.setText("");
            selectedNotesArea.clear();
            selectedNotesArea.setDisable(true);
            editEventButton.setDisable(true);
            deleteEventButton.setDisable(true);
            return;
        }
        selectedTitleLabel.setText(selectedEvent.getTitle());
        selectedTimeLabel.setText(selectedEvent.getStartTime().toLocalDate() + " "
                + selectedEvent.getStartTime().format(TIME_FMT) + " -> "
                + selectedEvent.getEndTime().format(TIME_FMT));

        String calName = calendarNameMap.getOrDefault(selectedEvent.getCalendarId(), "General");
        selectedNotesArea.setDisable(false);
        String notes = selectedEvent.getNotes();
        if (notes != null && !notes.isBlank()) {
            selectedNotesArea.setText(notes);
        } else {
            selectedNotesArea.clear();
        }

        if (selectedEvent.getSource() == EventSource.RECURRING) {
            RecurringRule matchingRule = findRuleForOccurrence(selectedEvent);
            if (matchingRule != null) {
                selectedMetaLabel.setText(calName + " · Weekly (" + formatDays(matchingRule) + ")");
                editEventButton.setDisable(false);
                deleteEventButton.setDisable(false);
            } else {
                selectedMetaLabel.setText(calName + " · Weekly");
                editEventButton.setDisable(true);
                deleteEventButton.setDisable(true);
            }
        } else {
            selectedMetaLabel.setText(calName + " · " + selectedEvent.getSource().name());
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
        sb.append("Added via ").append(res.getParseResult().getParserUsed()).append(": ");
        sb.append(res.getAddedEvents()).append(" event(s), ");
        sb.append(res.getAddedRules()).append(" weekly event(s)");
        if (!res.getParseResult().getWarnings().isEmpty()) {
            sb.append(" · ").append(String.join("; ", res.getParseResult().getWarnings()));
        }
        return sb.toString();
    }

    private String formatImportSummary(IcsImportSummary summary, String calendarName) {
        String text = "Imported " + summary.getImportedEvents() + " events and "
                + summary.getImportedRules() + " weekly events into " + calendarName + ".";
        if (!summary.getWarnings().isEmpty()) {
            text = text + " Warnings: " + String.join("; ", summary.getWarnings());
        }
        return text;
    }
}
