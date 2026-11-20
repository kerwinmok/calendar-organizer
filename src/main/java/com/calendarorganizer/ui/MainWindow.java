package com.calendarorganizer.ui;

import com.calendarorganizer.course.CourseExporter;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.llm.ParserMode;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.storage.StorageStats;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

public class MainWindow extends BorderPane {

    private final CalendarService calendarService;
    private final CalendarTabView calendarTabView;
    private final CourseTabView courseTabView;

    private final Button calendarNavBtn = new Button("Calendar");
    private final Button coursesNavBtn = new Button("Courses");

    private final TextField sentenceField = new TextField();
    private final ComboBox<ParserMode> parserModeBox = new ComboBox<>();
    private final Button addSentenceBtn = new Button("Add");
    private final HBox calendarCommandBox;

    private final TextField courseSearchField = new TextField();
    private final HBox courseCommandBox;

    private final Button autoScheduleBtn = new Button("Auto-Schedule");
    private final MenuButton icsMenuBtn = new MenuButton("ICS");
    private final HBox calendarActionsBox;

    private final Button exportCourseBtn = new Button("Export Course");
    private final Button exportAllBtn = new Button("Export All");
    private final HBox courseActionsBox;

    private final Label weekLabel = new Label();
    private final HBox calendarBottomLeft;
    private final Label coursePathBottomLabel = new Label();

    private final Label statusLabel = new Label("Ready.");
    private final Label statsPill = new Label();

    private UiTheme activeTheme;
    private boolean showingCalendar = true;

    public MainWindow(CalendarService calendarService,
                      CourseManager courseManager,
                      CourseExporter courseExporter) {
        this.calendarService = calendarService;
        this.calendarTabView = new CalendarTabView(calendarService);
        this.courseTabView = new CourseTabView(courseManager, courseExporter);
        this.activeTheme = UiTheme.fromId(calendarService.loadTheme());

        this.calendarCommandBox = buildCalendarCommandBox();
        this.courseCommandBox = buildCourseCommandBox();
        this.calendarActionsBox = buildCalendarActionsBox();
        this.courseActionsBox = buildCourseActionsBox();
        this.calendarBottomLeft = buildCalendarBottomLeft();

        this.calendarTabView.setOnStatusChanged(this::updateStatus);
        this.calendarTabView.setOnDataChanged(this::refreshFooter);
        this.courseTabView.setOnStatusChanged(this::updateStatus);
        this.courseTabView.setOnStateChanged(this::refreshFooter);

        setTop(buildTopBar());
        setBottom(buildBottomBar());

        applyTheme(activeTheme);
        switchToCalendar();
    }

    private HBox buildTopBar() {
        Button sidebarToggleBtn = new Button("Panel");
        sidebarToggleBtn.setOnAction(e -> {
            if (showingCalendar) {
                calendarTabView.toggleInspector();
            } else {
                courseTabView.toggleRail();
            }
        });

        calendarNavBtn.getStyleClass().add("lc-segment");
        coursesNavBtn.getStyleClass().add("lc-segment");
        calendarNavBtn.setOnAction(e -> switchToCalendar());
        coursesNavBtn.setOnAction(e -> switchToCourses());

        HBox navGroup = new HBox(2, calendarNavBtn, coursesNavBtn);
        navGroup.getStyleClass().add("lc-segment-group");

        HBox leftCluster = new HBox(8, sidebarToggleBtn, navGroup);
        leftCluster.setAlignment(Pos.CENTER_LEFT);

        Region leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        Button settingsBtn = new Button("Settings");
        settingsBtn.setOnAction(e -> openSettingsDialog());

        HBox rightCluster = new HBox(8, calendarActionsBox, courseActionsBox, settingsBtn);
        rightCluster.setAlignment(Pos.CENTER_RIGHT);

        HBox topBar = new HBox(10, leftCluster, leftSpacer, calendarCommandBox, courseCommandBox, rightSpacer, rightCluster);
        topBar.getStyleClass().add("lc-topbar");
        topBar.setAlignment(Pos.CENTER_LEFT);
        return topBar;
    }

    private HBox buildCalendarCommandBox() {
        sentenceField.setPromptText("Add by sentence: \"i work mon-thurs 7-6pm\" or \"work out and study, sleep at 12\"");
        sentenceField.getStyleClass().add("lc-command-field");
        sentenceField.setPrefWidth(420);
        sentenceField.setMaxWidth(540);
        HBox.setHgrow(sentenceField, Priority.ALWAYS);
        sentenceField.setOnAction(e -> submitSentence());

        parserModeBox.getItems().setAll(ParserMode.values());
        parserModeBox.setValue(ParserMode.AUTO);

        addSentenceBtn.getStyleClass().add("lc-btn-primary");
        addSentenceBtn.setOnAction(e -> submitSentence());

        HBox box = new HBox(6, sentenceField, parserModeBox, addSentenceBtn);
        box.setAlignment(Pos.CENTER);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private HBox buildCourseCommandBox() {
        courseSearchField.setPromptText("Filter courses (e.g. CMPT 201)...");
        courseSearchField.getStyleClass().add("lc-command-field");
        courseSearchField.setPrefWidth(340);
        courseSearchField.setMaxWidth(440);
        courseSearchField.textProperty().addListener((obs, oldVal, newVal) -> courseTabView.setFilterText(newVal));

        HBox box = new HBox(courseSearchField);
        box.setAlignment(Pos.CENTER);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private HBox buildCalendarActionsBox() {
        Button newEventBtn = new Button("+ Event");
        newEventBtn.setOnAction(e -> calendarTabView.openNewEventDialogDefault());
        autoScheduleBtn.setOnAction(e -> calendarTabView.rescheduleCurrentWeek());

        MenuItem importFileItem = new MenuItem("Import .ics File...");
        importFileItem.setOnAction(e -> calendarTabView.importIcsFile());
        MenuItem pasteIcsItem = new MenuItem("Paste .ics Text...");
        pasteIcsItem.setOnAction(e -> calendarTabView.pasteIcsText(activeTheme));
        MenuItem exportIcsItem = new MenuItem("Export .ics...");
        exportIcsItem.setOnAction(e -> calendarTabView.exportIcsFile());
        icsMenuBtn.getItems().setAll(importFileItem, pasteIcsItem, exportIcsItem);

        HBox box = new HBox(6, newEventBtn, autoScheduleBtn, icsMenuBtn);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    private HBox buildCourseActionsBox() {
        exportCourseBtn.setOnAction(e -> courseTabView.exportSelectedCourse());
        exportAllBtn.setOnAction(e -> courseTabView.exportAllCourses());

        HBox box = new HBox(6, exportCourseBtn, exportAllBtn);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    private HBox buildCalendarBottomLeft() {
        Button prevBtn = new Button("<");
        prevBtn.setOnAction(e -> calendarTabView.shiftWeek(-1));
        Button todayBtn = new Button("Today");
        todayBtn.setOnAction(e -> calendarTabView.goToday());
        Button nextBtn = new Button(">");
        nextBtn.setOnAction(e -> calendarTabView.shiftWeek(1));

        weekLabel.getStyleClass().add("section-heading");
        HBox box = new HBox(6, prevBtn, todayBtn, nextBtn, weekLabel);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private HBox buildBottomBar() {
        coursePathBottomLabel.getStyleClass().add("dim-label");
        statusLabel.getStyleClass().add("status-label");
        statsPill.getStyleClass().add("stats-pill");

        Region spacerLeft = new Region();
        HBox.setHgrow(spacerLeft, Priority.ALWAYS);
        Region spacerRight = new Region();
        HBox.setHgrow(spacerRight, Priority.ALWAYS);

        HBox bottomBar = new HBox(10,
                calendarBottomLeft,
                coursePathBottomLabel,
                spacerLeft,
                statusLabel,
                spacerRight,
                statsPill);
        bottomBar.getStyleClass().add("lc-bottombar");
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        return bottomBar;
    }

    private void switchToCalendar() {
        showingCalendar = true;
        setCenter(calendarTabView);
        updateModeVisibility();
        statusLabel.setText(calendarTabView.getLastStatus());
        refreshFooter();
    }

    private void switchToCourses() {
        showingCalendar = false;
        setCenter(courseTabView);
        updateModeVisibility();
        statusLabel.setText(courseTabView.getLastStatus());
        refreshFooter();
    }

    private void updateModeVisibility() {
        calendarNavBtn.getStyleClass().remove("lc-segment-active");
        coursesNavBtn.getStyleClass().remove("lc-segment-active");
        if (showingCalendar) {
            calendarNavBtn.getStyleClass().add("lc-segment-active");
        } else {
            coursesNavBtn.getStyleClass().add("lc-segment-active");
        }

        calendarCommandBox.setVisible(showingCalendar);
        calendarCommandBox.setManaged(showingCalendar);
        calendarActionsBox.setVisible(showingCalendar);
        calendarActionsBox.setManaged(showingCalendar);
        calendarBottomLeft.setVisible(showingCalendar);
        calendarBottomLeft.setManaged(showingCalendar);

        boolean coursesMode = !showingCalendar;
        courseCommandBox.setVisible(coursesMode);
        courseCommandBox.setManaged(coursesMode);
        courseActionsBox.setVisible(coursesMode);
        courseActionsBox.setManaged(coursesMode);
        coursePathBottomLabel.setVisible(coursesMode);
        coursePathBottomLabel.setManaged(coursesMode);
    }

    private void submitSentence() {
        String text = sentenceField.getText();
        ParserMode mode = parserModeBox.getValue();
        addSentenceBtn.setDisable(true);
        calendarTabView.submitSentence(text, mode, () -> {
            addSentenceBtn.setDisable(false);
            if (text != null && !text.isBlank()) {
                sentenceField.clear();
            }
        });
    }

    private void openSettingsDialog() {
        SettingsDialog dialog = new SettingsDialog(
                getScene().getWindow(),
                calendarService,
                activeTheme,
                this::applyTheme,
                () -> {
                    calendarTabView.refreshView();
                    refreshFooter();
                },
                this::updateStatus
        );
        dialog.showAndWait();
    }

    public void applyTheme(UiTheme theme) {
        this.activeTheme = theme;
        for (UiTheme candidate : UiTheme.values()) {
            getStyleClass().remove(candidate.getStyleClass());
        }
        getStyleClass().add(theme.getStyleClass());
        calendarTabView.setActiveTheme(theme);
        courseTabView.setActiveTheme(theme);
    }

    private void updateStatus(String status) {
        statusLabel.setText(status);
    }

    private void refreshFooter() {
        weekLabel.setText(calendarTabView.getWeekRangeLabel());
        coursePathBottomLabel.setText(courseTabView.getActiveLocationSummary());
        exportCourseBtn.setDisable(!courseTabView.hasSelectedCourse());
        exportAllBtn.setDisable(!courseTabView.hasCourses());

        StorageStats stats = calendarService.getStorageStats();
        statsPill.setText(stats.getEventCount() + " events · " + stats.formatSize());
    }
}
