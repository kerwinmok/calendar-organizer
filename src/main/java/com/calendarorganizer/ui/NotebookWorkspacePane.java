package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.notebook.GeminiNotebookSyncService;
import com.calendarorganizer.notebook.NotebookConfig;
import com.calendarorganizer.notebook.NotebookInfo;
import com.calendarorganizer.notebook.NotebookSyncResult;
import com.calendarorganizer.service.AppExecutors;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.workspace.WindowsWindowSnapper;
import com.calendarorganizer.workspace.WorkspaceLayoutConfig;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class NotebookWorkspacePane extends VBox implements WorkspacePaneContent {

    private final GeminiNotebookSyncService syncService;
    private final CalendarService calendarService;
    private final WorkspaceLayoutConfig.PaneState paneState;
    private final Runnable onFlushNotesRequested;
    private final Runnable onOpenConnectRequested;
    private final Consumer<String> onStatusChanged;
    private final Runnable onLayoutStateChanged;
    private final Supplier<LocalDate> activeWeekSupplier;

    private final Label statusPill = new Label("Not Connected");
    private final Button connectBtn = new Button("Connect...");
    private final Button refreshBtn = new Button("Refresh");
    private final ComboBox<Course> courseBox = new ComboBox<>();
    private final Button syncCourseBtn = new Button("Sync Course");
    private final Button syncScheduleBtn = new Button("Sync Schedule");
    private final Button syncAllBtn = new Button("Sync All");
    private final Button openWebBtn = new Button("Open in Browser");
    private final Button snapWebBtn = new Button("Snap Beside App");
    private final Label feedbackLabel = new Label("Connect a Google Cloud Desktop OAuth client or click Open in Browser to use Gemini Notebook.");
    private final ListView<NotebookInfo> notebooksList = new ListView<>();

    private final AtomicLong listSeq = new AtomicLong(0L);
    private boolean updatingCourses;
    private boolean busy;

    public NotebookWorkspacePane(
            GeminiNotebookSyncService syncService,
            CalendarService calendarService,
            WorkspaceLayoutConfig.PaneState paneState,
            Supplier<LocalDate> activeWeekSupplier,
            Runnable onFlushNotesRequested,
            Runnable onOpenConnectRequested,
            Consumer<String> onStatusChanged,
            Runnable onLayoutStateChanged
    ) {
        this.syncService = syncService;
        this.calendarService = calendarService;
        this.paneState = paneState;
        this.activeWeekSupplier = activeWeekSupplier;
        this.onFlushNotesRequested = onFlushNotesRequested;
        this.onOpenConnectRequested = onOpenConnectRequested;
        this.onStatusChanged = onStatusChanged;
        this.onLayoutStateChanged = onLayoutStateChanged;

        setSpacing(10);
        setPadding(new Insets(10));

        VBox headerCard = buildHeaderCard();
        VBox syncCard = buildSyncCard();

        feedbackLabel.setWrapText(true);
        feedbackLabel.setMaxWidth(Double.MAX_VALUE);
        feedbackLabel.getStyleClass().add("workspace-status-banner");

        Label listHeading = new Label("Remote Gemini Notebooks (double-click to open)");
        listHeading.getStyleClass().add("section-heading");

        configureNotebooksList();
        VBox.setVgrow(notebooksList, Priority.ALWAYS);

        getChildren().addAll(headerCard, syncCard, feedbackLabel, listHeading, notebooksList);
        refreshConnectionUi();
    }

    private VBox buildHeaderCard() {
        Label title = new Label("Gemini Notebook Direct Sync");
        title.getStyleClass().add("section-heading");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        connectBtn.setOnAction(e -> {
            if (onOpenConnectRequested != null) {
                onOpenConnectRequested.run();
            }
            refreshConnectionUi();
            if (syncService.loadConfig().isSignedIn()) {
                loadRemoteNotebooksAsync();
            }
        });
        refreshBtn.setOnAction(e -> {
            refreshConnectionUi();
            loadRemoteNotebooksAsync();
        });

        HBox topRow = new HBox(8, title, statusPill, spacer, connectBtn, refreshBtn);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label("Uses notebook.googleapis.com/v1 + SHA-256 skip dedupe. Secrets live in Windows Credential Manager.");
        note.getStyleClass().add("dim-label");
        note.setWrapText(true);

        VBox card = new VBox(6, topRow, note);
        card.getStyleClass().add("workspace-card");
        card.setPadding(new Insets(10));
        return card;
    }

    private VBox buildSyncCard() {
        WorkspaceUiHelpers.configureCourseBox(courseBox, "Select course...", 190);
        courseBox.setOnAction(e -> {
            if (updatingCourses) {
                return;
            }
            Course selected = courseBox.getValue();
            if (selected != null) {
                paneState.setTargetCourse(selected.getFolderName());
                if (onLayoutStateChanged != null) {
                    onLayoutStateChanged.run();
                }
            }
            updateButtonAvailability();
        });

        syncCourseBtn.getStyleClass().add("lc-btn-primary");
        syncCourseBtn.setOnAction(e -> handleSyncCourse());
        syncScheduleBtn.setOnAction(e -> handleSyncSchedule());
        syncAllBtn.setOnAction(e -> handleSyncAll());
        openWebBtn.setOnAction(e -> handleOpenNotebookWeb(false));
        snapWebBtn.setOnAction(e -> handleOpenNotebookWeb(true));

        FlowPane syncButtons = new FlowPane(6, 6, syncCourseBtn, syncScheduleBtn, syncAllBtn, openWebBtn, snapWebBtn);
        syncButtons.setAlignment(Pos.CENTER_LEFT);

        HBox courseRow = new HBox(8, new Label("Course"), courseBox);
        courseRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, courseRow, syncButtons);
        card.getStyleClass().add("workspace-card");
        card.setPadding(new Insets(10));
        return card;
    }

    private void configureNotebooksList() {
        notebooksList.setPlaceholder(new Label("No notebooks loaded yet."));
        notebooksList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(NotebookInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                } else {
                    String title = item.getDisplayName();
                    if (title.isBlank()) {
                        title = item.getName();
                    }
                    setText(title + "  (" + item.getName() + ")");
                }
            }
        });
        notebooksList.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                NotebookInfo selected = notebooksList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    openUrlSafe(notebookUrlFromResource(selected.getName()), false);
                }
            }
        });
    }

    private void refreshConnectionUi() {
        NotebookConfig cfg = syncService.loadConfig();
        statusPill.getStyleClass().removeAll("notebook-pill", "notebook-pill-connected");
        if (cfg.isSignedIn()) {
            String label = "Connected";
            if (!cfg.getAccountEmail().isBlank()) {
                label = "Connected: " + cfg.getAccountEmail();
            }
            statusPill.setText(label);
            statusPill.getStyleClass().add("notebook-pill-connected");
        } else {
            statusPill.setText("Not Connected");
            statusPill.getStyleClass().add("notebook-pill");
        }
        updateButtonAvailability();
    }

    private void updateButtonAvailability() {
        boolean signedIn = syncService.loadConfig().isSignedIn();
        boolean hasCourse = courseBox.getValue() != null;
        connectBtn.setDisable(busy);
        refreshBtn.setDisable(busy);
        syncCourseBtn.setDisable(busy || !hasCourse);
        syncScheduleBtn.setDisable(busy);
        syncAllBtn.setDisable(busy);
        openWebBtn.setDisable(busy);
        snapWebBtn.setDisable(busy);
        if (!signedIn && !busy) {
            setBannerInfo("Sign in via Connect... to use Direct API Sync, or use Open in Browser / Snap Beside App directly.");
        }
    }

    private void loadRemoteNotebooksAsync() {
        if (!syncService.loadConfig().isSignedIn()) {
            notebooksList.getItems().clear();
            return;
        }
        long seq = listSeq.incrementAndGet();
        setBannerInfo("Loading notebooks from Gemini Notebook API...");
        AppExecutors.ioExecutor().execute(() -> {
            try {
                List<NotebookInfo> remote = syncService.listRemoteNotebooks();
                Platform.runLater(() -> {
                    if (listSeq.get() != seq) {
                        return;
                    }
                    notebooksList.getItems().setAll(remote);
                    setBannerInfo("Loaded " + remote.size() + " Gemini Notebook(s).");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (listSeq.get() != seq) {
                        return;
                    }
                    refreshConnectionUi();
                    setBannerError(ex.getMessage());
                });
            }
        });
    }

    private void handleSyncCourse() {
        Course course = courseBox.getValue();
        if (course == null || !ensureSignedInPrompt()) {
            return;
        }
        flushNotesSafely();
        runSyncAsync("Syncing " + course.getDisplayName() + "...", () -> List.of(syncService.syncCourse(course)));
    }

    private void handleSyncSchedule() {
        if (!ensureSignedInPrompt()) {
            return;
        }
        LocalDate week = resolveWeekStart();
        runSyncAsync("Syncing schedule to Gemini Notebook...", () -> List.of(syncService.syncSchedule(week)));
    }

    private void handleSyncAll() {
        if (!ensureSignedInPrompt()) {
            return;
        }
        flushNotesSafely();
        LocalDate week = resolveWeekStart();
        runSyncAsync("Syncing schedule and all courses...", () -> syncService.syncAll(week));
    }

    private boolean ensureSignedInPrompt() {
        if (syncService.loadConfig().isSignedIn()) {
            return true;
        }
        if (onOpenConnectRequested != null) {
            onOpenConnectRequested.run();
        }
        refreshConnectionUi();
        return syncService.loadConfig().isSignedIn();
    }

    private void runSyncAsync(String startMsg, Callable<List<NotebookSyncResult>> task) {
        busy = true;
        updateButtonAvailability();
        setBannerInfo(startMsg);
        publishStatus(startMsg);

        AppExecutors.ioExecutor().execute(() -> {
            try {
                List<NotebookSyncResult> results = task.call();
                String summary = GeminiNotebookSyncService.formatBatchSummary(results);
                Platform.runLater(() -> {
                    busy = false;
                    refreshConnectionUi();
                    setBannerInfo(summary);
                    publishStatus(summary);
                    loadRemoteNotebooksAsync();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    busy = false;
                    refreshConnectionUi();
                    String err = ex.getMessage();
                    if (err == null || err.isBlank()) {
                        err = "Gemini Notebook sync failed.";
                    }
                    setBannerError(err);
                    publishStatus(err);
                });
            }
        });
    }

    private void handleOpenNotebookWeb(boolean snapBeside) {
        Course course = courseBox.getValue();
        String url;
        if (course != null) {
            url = syncService.getCourseNotebookWebUrl(course);
        } else {
            url = syncService.getScheduleNotebookWebUrl();
        }
        openUrlSafe(url, snapBeside);
    }

    private void openUrlSafe(String url, boolean snapBeside) {
        setBannerInfo("Opening " + url + "...");
        AppExecutors.ioExecutor().execute(() -> {
            try {
                if (snapBeside) {
                    WindowsWindowSnapper.SnapOutcome outcome = WindowsWindowSnapper.launchUrlAndSnapBrowser(
                            url,
                            List.of("notebooklm", "gemini notebook", "notebook.google"),
                            0.50,
                            true,
                            "Gemini Notebook"
                    );
                    Platform.runLater(() -> {
                        setBannerInfo(outcome.getMessage());
                        publishStatus(outcome.getMessage());
                    });
                } else {
                    WindowsWindowSnapper.openUrlInDefaultBrowser(url);
                    Platform.runLater(() -> {
                        setBannerInfo("Opened Gemini Notebook in your default browser.");
                        publishStatus("Opened Gemini Notebook in default browser.");
                    });
                }
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setBannerError("Could not open browser: " + ex.getMessage());
                    publishStatus("Could not open browser: " + ex.getMessage());
                });
            }
        });
    }

    private String notebookUrlFromResource(String resourceName) {
        if (resourceName != null && resourceName.startsWith("notebooks/")) {
            String id = resourceName.substring("notebooks/".length()).trim();
            if (!id.isBlank() && !id.contains("/")) {
                return "https://notebooklm.google.com/notebook/" + id;
            }
        }
        return "https://notebooklm.google.com/";
    }

    private LocalDate resolveWeekStart() {
        if (activeWeekSupplier != null) {
            LocalDate week = activeWeekSupplier.get();
            if (week != null) {
                return week;
            }
        }
        return calendarService.weekStartOf(LocalDate.now());
    }

    private void flushNotesSafely() {
        if (onFlushNotesRequested != null) {
            onFlushNotesRequested.run();
        }
    }

    private void setBannerInfo(String message) {
        feedbackLabel.getStyleClass().remove("workspace-error-banner");
        if (!feedbackLabel.getStyleClass().contains("workspace-status-banner")) {
            feedbackLabel.getStyleClass().add("workspace-status-banner");
        }
        feedbackLabel.setText(message);
    }

    private void setBannerError(String message) {
        feedbackLabel.getStyleClass().remove("workspace-status-banner");
        if (!feedbackLabel.getStyleClass().contains("workspace-error-banner")) {
            feedbackLabel.getStyleClass().add("workspace-error-banner");
        }
        feedbackLabel.setText(message);
    }

    private void publishStatus(String message) {
        if (onStatusChanged != null && message != null && !message.isBlank()) {
            onStatusChanged.accept(message);
        }
    }

    @Override
    public Region getView() {
        return this;
    }

    @Override
    public void refreshCourses(List<Course> courses, Course preferredCourse) {
        updatingCourses = true;
        try {
            courseBox.getItems().setAll(courses);
            Course target = WorkspaceUiHelpers.resolveTargetCourse(courses, paneState.getTargetCourse(), preferredCourse);
            courseBox.setValue(target);
        } finally {
            updatingCourses = false;
        }
        refreshConnectionUi();
        if (syncService.loadConfig().isSignedIn() && notebooksList.getItems().isEmpty()) {
            loadRemoteNotebooksAsync();
        }
    }

    @Override
    public void flushPendingEdits() {
    }

    @Override
    public void releaseResources() {
        listSeq.incrementAndGet();
        notebooksList.getItems().clear();
    }

    @Override
    public void setActiveTheme(UiTheme resolvedTheme) {
    }
}
