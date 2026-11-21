package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseExporter;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.course.CourseSortOrder;
import com.calendarorganizer.llm.ParserMode;
import com.calendarorganizer.service.AppExecutors;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.storage.StorageStats;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicLong;

public class MainWindow extends BorderPane {

    private final CalendarService calendarService;
    private final CourseManager courseManager;
    private final CourseExporter courseExporter;
    private final CalendarTabView calendarTabView;
    private CourseTabView courseTabView;
    private LectureRenderPane lectureRenderPane;
    private final AtomicLong statsRequestSeq = new AtomicLong(0L);

    private final StackPane mainContentHost = new StackPane();
    private final SplitPane workspaceSplit = new SplitPane();
    private final HBox topBar;
    private final HBox bottomBar;

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

    private final Button renderCourseBtn = new Button("Render");
    private final Button exportCourseBtn = new Button("Export Course");
    private final Button exportAllBtn = new Button("Export All");
    private final HBox courseActionsBox;

    private final Label weekLabel = new Label();
    private final HBox calendarBottomLeft;
    private final Label coursePathBottomLabel = new Label();

    private final Label statusLabel = new Label("Ready.");
    private final Label statsPill = new Label();

    private UiTheme activeTheme;
    private final WindowsThemeDetector themeDetector;
    private volatile boolean systemDarkMode;
    private SettingsDialog openSettingsDialog;
    private boolean showingCalendar = true;
    private double savedSplitDivider = 0.50;
    private boolean renderFullScreen;
    private boolean fullScreenListenerAttached;

    public MainWindow(CalendarService calendarService,
                      CourseManager courseManager,
                      CourseExporter courseExporter) {
        this.calendarService = calendarService;
        this.courseManager = courseManager;
        this.courseExporter = courseExporter;
        this.calendarTabView = new CalendarTabView(calendarService);
        this.activeTheme = UiTheme.fromId(calendarService.loadTheme());
        this.themeDetector = new WindowsThemeDetector(this::handleSystemThemeChanged);
        this.systemDarkMode = themeDetector.isDarkMode();
        this.themeDetector.start();

        this.calendarCommandBox = buildCalendarCommandBox();
        this.courseCommandBox = buildCourseCommandBox();
        this.calendarActionsBox = buildCalendarActionsBox();
        this.courseActionsBox = buildCourseActionsBox();
        this.calendarBottomLeft = buildCalendarBottomLeft();

        this.calendarTabView.setOnStatusChanged(this::updateStatus);
        this.calendarTabView.setOnDataChanged(this::refreshFooter);

        this.topBar = buildTopBar();
        this.bottomBar = buildBottomBar();
        this.workspaceSplit.getItems().setAll(mainContentHost);

        setTop(topBar);
        setCenter(workspaceSplit);
        setBottom(bottomBar);

        addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.F11 && isRenderPaneOpen()) {
                setRenderFullScreen(!renderFullScreen);
                ev.consume();
            }
        });

        applyTheme(activeTheme);
        switchToCalendar();
    }

    private CourseTabView ensureCourseTabView() {
        if (courseTabView == null) {
            CourseTabView view = new CourseTabView(courseManager, courseExporter);
            view.setActiveTheme(getResolvedTheme());
            view.setOnStatusChanged(this::updateStatus);
            view.setOnStateChanged(this::refreshCourseFooterUi);
            view.setOnRenderRequested(this::openRenderPane);
            view.setOnSortOrderChanged(order ->
                    AppExecutors.ioExecutor().execute(() -> calendarService.saveCourseSortOrder(order.getId())));
            String filter = courseSearchField.getText();
            if (filter != null && !filter.isBlank()) {
                view.setFilterText(filter);
            }
            this.courseTabView = view;
            AppExecutors.ioExecutor().execute(() -> {
                CourseSortOrder savedOrder = CourseSortOrder.fromId(calendarService.loadCourseSortOrder());
                Platform.runLater(() -> {
                    if (courseTabView == view) {
                        view.setSortOrder(savedOrder);
                    }
                });
            });
        }
        return courseTabView;
    }

    private HBox buildTopBar() {
        Button sidebarToggleBtn = new Button("Panel");
        sidebarToggleBtn.setOnAction(e -> {
            if (showingCalendar) {
                calendarTabView.toggleInspector();
            } else if (courseTabView != null) {
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
        courseSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (courseTabView != null) {
                courseTabView.setFilterText(newVal);
            }
        });

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
        pasteIcsItem.setOnAction(e -> calendarTabView.pasteIcsText());
        MenuItem exportIcsItem = new MenuItem("Export .ics...");
        exportIcsItem.setOnAction(e -> calendarTabView.exportIcsFile());
        icsMenuBtn.getItems().setAll(importFileItem, pasteIcsItem, exportIcsItem);

        HBox box = new HBox(6, newEventBtn, autoScheduleBtn, icsMenuBtn);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    private HBox buildCourseActionsBox() {
        renderCourseBtn.getStyleClass().add("lc-btn-primary");
        renderCourseBtn.setDisable(true);
        renderCourseBtn.setOnAction(e -> openRenderPane());

        exportCourseBtn.setOnAction(e -> {
            if (courseTabView != null) {
                courseTabView.exportSelectedCourse();
            }
        });
        exportAllBtn.setOnAction(e -> {
            if (courseTabView != null) {
                courseTabView.exportAllCourses();
            }
        });

        HBox box = new HBox(6, renderCourseBtn, exportCourseBtn, exportAllBtn);
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
        if (courseTabView != null) {
            courseTabView.releaseTransientCaches(!isRenderPaneOpen());
        }
        mainContentHost.getChildren().setAll(calendarTabView);
        updateModeVisibility();
        statusLabel.setText(calendarTabView.getLastStatus());
        refreshFooter();
    }

    private void switchToCourses() {
        showingCalendar = false;
        CourseTabView view = ensureCourseTabView();
        mainContentHost.getChildren().setAll(view);
        view.ensureActivePreviewLoaded();
        updateModeVisibility();
        statusLabel.setText(view.getLastStatus());
        refreshCourseFooterUi();
    }

    private boolean isRenderPaneOpen() {
        return lectureRenderPane != null
                && (workspaceSplit.getItems().contains(lectureRenderPane) || renderFullScreen);
    }

    private void openRenderPane() {
        CourseTabView view = ensureCourseTabView();
        Course selectedCourse = view.getSelectedCourse();
        if (selectedCourse == null) {
            updateStatus("Select a course first to render its files.");
            return;
        }
        view.flushPendingEdits();

        if (lectureRenderPane == null) {
            lectureRenderPane = new LectureRenderPane(courseManager, view.getPreviewer());
            lectureRenderPane.setOnFullScreenToggle(this::setRenderFullScreen);
            lectureRenderPane.setOnCloseRequested(this::closeRenderPane);
            lectureRenderPane.setOnStatusChanged(this::updateStatus);
        }

        if (!renderFullScreen && !workspaceSplit.getItems().contains(lectureRenderPane)) {
            workspaceSplit.getItems().setAll(mainContentHost, lectureRenderPane);
            workspaceSplit.setDividerPositions(savedSplitDivider);
        }

        lectureRenderPane.loadCourseContext(
                selectedCourse,
                view.getSelectedSection(),
                view.getSelectedItem(),
                view.getActiveSortOrder()
        );
    }

    private void closeRenderPane() {
        if (renderFullScreen) {
            setRenderFullScreen(false);
        }
        if (workspaceSplit.getDividers().size() == 1) {
            savedSplitDivider = workspaceSplit.getDividers().get(0).getPosition();
        }
        workspaceSplit.getItems().setAll(mainContentHost);
        if (lectureRenderPane != null) {
            lectureRenderPane.releaseResources();
        }
    }

    private void setRenderFullScreen(boolean enable) {
        Stage stage = getScene() != null && getScene().getWindow() instanceof Stage
                ? (Stage) getScene().getWindow()
                : null;
        if (stage != null && !fullScreenListenerAttached) {
            fullScreenListenerAttached = true;
            stage.fullScreenProperty().addListener((obs, wasFull, isFull) -> {
                if (!isFull && renderFullScreen) {
                    restoreSideBySideFromFullScreen();
                }
            });
        }

        if (enable) {
            if (lectureRenderPane == null || renderFullScreen) {
                return;
            }
            if (workspaceSplit.getItems().contains(lectureRenderPane) && workspaceSplit.getDividers().size() == 1) {
                savedSplitDivider = workspaceSplit.getDividers().get(0).getPosition();
            }
            renderFullScreen = true;
            lectureRenderPane.setFullScreenState(true);
            setTop(null);
            setBottom(null);
            workspaceSplit.getItems().setAll(lectureRenderPane);
            if (stage != null) {
                stage.setFullScreenExitHint("");
                stage.setFullScreen(true);
            }
        } else {
            if (stage != null && stage.isFullScreen()) {
                stage.setFullScreen(false);
            }
            restoreSideBySideFromFullScreen();
        }
    }

    private void restoreSideBySideFromFullScreen() {
        if (!renderFullScreen) {
            return;
        }
        renderFullScreen = false;
        setTop(topBar);
        setBottom(bottomBar);
        if (lectureRenderPane != null) {
            lectureRenderPane.setFullScreenState(false);
            workspaceSplit.getItems().setAll(mainContentHost, lectureRenderPane);
            workspaceSplit.setDividerPositions(savedSplitDivider);
        } else {
            workspaceSplit.getItems().setAll(mainContentHost);
        }
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
        calendarTabView.submitSentence(text, mode, succeeded -> {
            addSentenceBtn.setDisable(false);
            if (Boolean.TRUE.equals(succeeded)) {
                sentenceField.clear();
            }
        });
    }

    private void openSettingsDialog() {
        SettingsDialog dialog = new SettingsDialog(
                getScene().getWindow(),
                calendarService,
                activeTheme,
                () -> systemDarkMode,
                this::applyTheme,
                () -> {
                    calendarTabView.clearSelection();
                    calendarTabView.refreshView();
                    refreshFooter();
                },
                this::updateStatus
        );
        this.openSettingsDialog = dialog;
        try {
            dialog.showAndWait();
        } finally {
            this.openSettingsDialog = null;
        }
    }

    public void flushPendingEdits() {
        if (courseTabView != null) {
            courseTabView.flushPendingEdits();
        }
    }

    public void shutdown() {
        flushPendingEdits();
        if (lectureRenderPane != null) {
            lectureRenderPane.releaseResources();
        }
        if (courseTabView != null) {
            courseTabView.shutdownPreviewer();
        }
        themeDetector.close();
        AppExecutors.shutdown();
    }

    public UiTheme getResolvedTheme() {
        return activeTheme.resolve(systemDarkMode);
    }

    public void applyNativeTitleBar() {
        WindowsTitleBar.applyToAllProcessWindows(getResolvedTheme());
    }

    private void handleSystemThemeChanged(boolean nextDark) {
        this.systemDarkMode = nextDark;
        Platform.runLater(() -> {
            if (activeTheme == UiTheme.SYSTEM) {
                applyTheme(UiTheme.SYSTEM);
                if (openSettingsDialog != null) {
                    openSettingsDialog.refreshResolvedTheme(nextDark);
                }
            }
        });
    }

    public void applyTheme(UiTheme theme) {
        this.activeTheme = theme;
        UiTheme resolved = theme.resolve(systemDarkMode);
        for (UiTheme candidate : UiTheme.values()) {
            getStyleClass().remove(candidate.getStyleClass());
        }
        getStyleClass().add(resolved.getStyleClass());
        calendarTabView.setActiveTheme(resolved);
        if (courseTabView != null) {
            courseTabView.setActiveTheme(resolved);
        }
        if (getScene() != null && getScene().getWindow() != null && getScene().getWindow().isShowing()) {
            applyNativeTitleBar();
        }
    }

    private void updateStatus(String status) {
        statusLabel.setText(status);
    }

    private void refreshFooter() {
        refreshCourseFooterUi();
        refreshStatsAsync();
    }

    private void refreshCourseFooterUi() {
        weekLabel.setText(calendarTabView.getWeekRangeLabel());
        if (courseTabView != null) {
            coursePathBottomLabel.setText(courseTabView.getActiveLocationSummary());
            boolean hasSelected = courseTabView.hasSelectedCourse();
            renderCourseBtn.setDisable(!hasSelected);
            exportCourseBtn.setDisable(!hasSelected);
            exportAllBtn.setDisable(!courseTabView.hasCourses());
        } else {
            coursePathBottomLabel.setText("No course file selected");
            renderCourseBtn.setDisable(true);
            exportCourseBtn.setDisable(true);
            exportAllBtn.setDisable(true);
        }
    }

    private void refreshStatsAsync() {
        long seq = statsRequestSeq.incrementAndGet();
        AppExecutors.ioExecutor().execute(() -> {
            StorageStats stats = calendarService.getStorageStats();
            Platform.runLater(() -> {
                if (statsRequestSeq.get() == seq) {
                    statsPill.setText(stats.getEventCount() + " events · " + stats.formatSize());
                }
            });
        });
    }
}
