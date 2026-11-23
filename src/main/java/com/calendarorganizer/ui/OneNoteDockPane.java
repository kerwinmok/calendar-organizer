package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.service.AppExecutors;
import com.calendarorganizer.workspace.WindowsWindowDocker;
import com.calendarorganizer.workspace.WindowsWindowSnapper;
import com.calendarorganizer.workspace.WorkspaceLayoutConfig;
import com.sun.jna.Pointer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class OneNoteDockPane extends BorderPane implements WorkspacePaneContent {

    private static final int DRAG_OUT_THRESHOLD_PX = 48;

    private final WorkspaceLayoutConfig.PaneState paneState;
    private final Consumer<String> onStatusChanged;

    private final Button dockOneNoteBtn = new Button("Dock OneNote");
    private final ComboBox<WindowsWindowDocker.WindowCandidate> windowBox = new ComboBox<>();
    private final Button refreshWindowsBtn = new Button("Refresh");
    private final Button dockSelectedBtn = new Button("Dock");
    private final Button undockBtn = new Button("Undock");
    private final Button sideBySideBtn = new Button("Side-by-Side");

    private final StackPane dockHost = new StackPane();
    private final VBox dropCard = new VBox(8);
    private final Label dropTitleLabel = new Label("Drag OneNote (or any app/browser tab) here to snap inside");
    private final Label dropSubtitleLabel = new Label(
            "Release the window over this pane to lock it flush beside your lectures and calendar.\n"
                    + "You can draw with your pen in OneNote and scroll PDFs on the left at the same time."
    );
    private final CheckBox acceptAnyWindowCheck = new CheckBox("Accept any dragged window (OneNote, browser tab, etc.)");
    private final Label exeStatusLabel = new Label();

    private final PauseTransition syncDebounce = new PauseTransition(Duration.millis(16));
    private final Timeline watcherTimeline;
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private final AtomicBoolean syncInFlight = new AtomicBoolean(false);

    private WindowsWindowDocker.DockedSession activeSession;
    private WindowsWindowSnapper.Rect lastSyncedTargetRect;
    private Pointer hoveredDragHwnd;
    private String hoveredDragTitle = "";
    private boolean hoveringDropZone;
    private boolean hostVisible = true;
    private boolean hostDetached;
    private boolean stageListenersAttached;
    private Stage attachedStage;

    public OneNoteDockPane(
            WorkspaceLayoutConfig.PaneState paneState,
            Consumer<String> onStatusChanged
    ) {
        this.paneState = paneState;
        this.onStatusChanged = onStatusChanged;

        setTop(buildSubBar());
        setCenter(buildDockHost());

        syncDebounce.setOnFinished(e -> syncDockedBoundsNow());

        watcherTimeline = new Timeline(new KeyFrame(Duration.millis(140), e -> pollNativeWindowStateAsync()));
        watcherTimeline.setCycleCount(Timeline.INDEFINITE);

        sceneProperty().addListener((obs, oldScene, newScene) -> handleSceneChanged(newScene));
        dockHost.layoutBoundsProperty().addListener((obs, oldB, newB) -> requestCoalescedBoundsSync());
        dockHost.localToSceneTransformProperty().addListener((obs, oldT, newT) -> requestCoalescedBoundsSync());

        refreshWindowListAsync();
        updateHostUiState();
    }

    private HBox buildSubBar() {
        dockOneNoteBtn.getStyleClass().add("lc-btn-primary");
        dockOneNoteBtn.setOnAction(e -> handleLaunchAndDockOneNote());

        windowBox.setPrefWidth(190);
        windowBox.setPromptText("Open windows...");

        refreshWindowsBtn.setOnAction(e -> refreshWindowListAsync());

        dockSelectedBtn.setOnAction(e -> handleDockSelectedCandidate());

        undockBtn.setDisable(true);
        undockBtn.setOnAction(e -> handleUndockRequested(true));

        sideBySideBtn.setOnAction(e -> handleSideBySideFallback());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox subBar = new HBox(6, dockOneNoteBtn, windowBox, refreshWindowsBtn, dockSelectedBtn, undockBtn, spacer, sideBySideBtn);
        subBar.getStyleClass().add("workspace-subbar");
        subBar.setAlignment(Pos.CENTER_LEFT);
        subBar.setPadding(new Insets(5, 8, 5, 8));
        return subBar;
    }

    private StackPane buildDockHost() {
        dockHost.getStyleClass().add("workspace-dock-host");
        dockHost.setMinSize(160, 140);

        dropTitleLabel.getStyleClass().add("section-heading");
        dropTitleLabel.setWrapText(true);

        dropSubtitleLabel.getStyleClass().add("dim-label");
        dropSubtitleLabel.setWrapText(true);

        acceptAnyWindowCheck.setSelected(true);

        Path oneNoteExe = WindowsWindowSnapper.findOneNoteExecutable();
        if (oneNoteExe != null) {
            exeStatusLabel.setText("OneNote Desktop ready: " + oneNoteExe);
        } else {
            exeStatusLabel.setText("OneNote Desktop will use Windows 'onenote:' launcher when clicked.");
        }
        exeStatusLabel.getStyleClass().add("dim-label");
        exeStatusLabel.setWrapText(true);

        dropCard.getStyleClass().add("workspace-card");
        dropCard.setMaxWidth(420);
        dropCard.setPadding(new Insets(16));
        dropCard.setAlignment(Pos.CENTER_LEFT);
        dropCard.getChildren().addAll(dropTitleLabel, dropSubtitleLabel, acceptAnyWindowCheck, exeStatusLabel);

        dockHost.getChildren().setAll(dropCard);
        return dockHost;
    }

    private void handleSceneChanged(Scene newScene) {
        detachStageListeners();
        if (newScene == null) {
            pauseWatcher();
            return;
        }
        newScene.windowProperty().addListener((obs, oldWin, newWin) -> attachStageListeners(newWin));
        attachStageListeners(newScene.getWindow());
        if (hostVisible) {
            startWatcherIfNeeded();
        }
    }

    private void attachStageListeners(Window win) {
        detachStageListeners();
        if (!(win instanceof Stage)) {
            return;
        }
        Stage stage = (Stage) win;
        this.attachedStage = stage;
        this.stageListenersAttached = true;

        stage.xProperty().addListener((obs, o, n) -> requestCoalescedBoundsSync());
        stage.yProperty().addListener((obs, o, n) -> requestCoalescedBoundsSync());
        stage.widthProperty().addListener((obs, o, n) -> requestCoalescedBoundsSync());
        stage.heightProperty().addListener((obs, o, n) -> requestCoalescedBoundsSync());
        stage.maximizedProperty().addListener((obs, o, n) -> requestCoalescedBoundsSync());
        stage.fullScreenProperty().addListener((obs, o, n) -> requestCoalescedBoundsSync());
        stage.showingProperty().addListener((obs, wasShowing, nowShowing) -> {
            if (nowShowing && hostVisible) {
                startWatcherIfNeeded();
                requestCoalescedBoundsSync();
            } else {
                pauseWatcher();
            }
        });
        stage.iconifiedProperty().addListener((obs, wasMin, isMin) -> {
            if (activeSession == null || !activeSession.isActive()) {
                return;
            }
            if (isMin) {
                AppExecutors.ioExecutor().execute(() -> {
                    Pointer appHwnd = WindowsWindowDocker.findAppWindow(ProcessHandle.current().pid());
                    WindowsWindowDocker.detachTemporarily(activeSession, appHwnd);
                });
            } else if (hostVisible) {
                Platform.runLater(() -> reattachActiveSession());
            }
        });
    }

    private void detachStageListeners() {
        stageListenersAttached = false;
        attachedStage = null;
    }

    private void refreshWindowListAsync() {
        long ownPid = ProcessHandle.current().pid();
        AppExecutors.ioExecutor().execute(() -> {
            List<WindowsWindowDocker.WindowCandidate> windows = WindowsWindowDocker.listDockableWindows(ownPid);
            Platform.runLater(() -> {
                windowBox.getItems().setAll(windows);
                if (!windows.isEmpty() && windowBox.getValue() == null) {
                    windowBox.setValue(windows.get(0));
                }
            });
        });
    }

    private void handleLaunchAndDockOneNote() {
        if (!WindowsWindowDocker.isWindows()) {
            publishStatus("In-pane window docking requires Windows.");
            return;
        }
        dockOneNoteBtn.setDisable(true);
        publishStatus("Looking for OneNote window to dock...");

        long ownPid = ProcessHandle.current().pid();
        AppExecutors.ioExecutor().execute(() -> {
            try {
                Pointer targetHwnd = findFirstOneNoteHwnd(ownPid);
                if (targetHwnd == null) {
                    Path exe = WindowsWindowSnapper.findOneNoteExecutable();
                    if (exe != null) {
                        new ProcessBuilder(exe.toString()).start();
                    } else {
                        WindowsWindowSnapper.openUrlInDefaultBrowser("onenote:");
                    }
                    for (int attempt = 0; attempt < 24; attempt++) {
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        targetHwnd = findFirstOneNoteHwnd(ownPid);
                        if (targetHwnd != null) {
                            break;
                        }
                    }
                }
                Pointer resolvedHwnd = targetHwnd;
                Platform.runLater(() -> {
                    dockOneNoteBtn.setDisable(false);
                    if (resolvedHwnd == null) {
                        publishStatus("Could not find an open OneNote window yet. Drag OneNote over this pane once open.");
                        refreshWindowListAsync();
                        return;
                    }
                    dockTargetWindow(resolvedHwnd, "Microsoft OneNote");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    dockOneNoteBtn.setDisable(false);
                    publishStatus("Failed launching OneNote: " + ex.getMessage());
                });
            }
        });
    }

    private static Pointer findFirstOneNoteHwnd(long ownPid) {
        List<WindowsWindowDocker.WindowCandidate> candidates = WindowsWindowDocker.listDockableWindows(ownPid);
        for (WindowsWindowDocker.WindowCandidate candidate : candidates) {
            if (candidate.isOneNote()) {
                return candidate.getHwnd();
            }
        }
        return null;
    }

    private void handleDockSelectedCandidate() {
        WindowsWindowDocker.WindowCandidate candidate = windowBox.getValue();
        if (candidate == null) {
            refreshWindowListAsync();
            publishStatus("Select a window first, or drag one into the OneNote Dock pane.");
            return;
        }
        dockTargetWindow(candidate.getHwnd(), candidate.getTitle());
    }

    private void dockTargetWindow(Pointer targetHwnd, String fallbackTitle) {
        if (targetHwnd == null || !WindowsWindowDocker.isWindows()) {
            return;
        }
        if (activeSession != null) {
            WindowsWindowDocker.undock(activeSession, false);
            activeSession = null;
        }

        WindowsWindowSnapper.Rect targetRect = computeCurrentHostPhysicalRect();
        if (targetRect == null) {
            publishStatus("Open the Workspace tab visibly before docking a window.");
            return;
        }

        long ownPid = ProcessHandle.current().pid();
        lastSyncedTargetRect = targetRect;
        paneState.setExternalPreset("Docked:" + fallbackTitle);

        AppExecutors.ioExecutor().execute(() -> {
            Pointer appHwnd = WindowsWindowDocker.findAppWindow(ownPid);
            WindowsWindowDocker.DockedSession session = WindowsWindowDocker.dockWindowIntoHost(
                    targetHwnd,
                    appHwnd,
                    targetRect
            );
            Platform.runLater(() -> {
                if (session == null) {
                    publishStatus("Could not dock selected window.");
                    return;
                }
                activeSession = session;
                hostDetached = false;
                clearHoverHighlight();
                updateHostUiState();
                startWatcherIfNeeded();
                publishStatus("Docked inside pane: " + session.getTitle() + " (drag its title bar out or click Undock to release).");
            });
        });
    }

    private void handleUndockRequested(boolean restoreBounds) {
        if (activeSession == null) {
            return;
        }
        WindowsWindowDocker.DockedSession session = activeSession;
        String title = session.getTitle();
        activeSession = null;
        lastSyncedTargetRect = null;
        hostDetached = false;
        updateHostUiState();

        AppExecutors.ioExecutor().execute(() -> WindowsWindowDocker.undock(session, restoreBounds));
        publishStatus("Undocked " + title + ".");
        refreshWindowListAsync();
    }

    private void handleSideBySideFallback() {
        handleUndockRequested(false);
        sideBySideBtn.setDisable(true);
        AppExecutors.ioExecutor().execute(() -> {
            try {
                WindowsWindowSnapper.SnapOutcome out = WindowsWindowSnapper.launchAndSnapOneNoteDesktop(0.50, true);
                Platform.runLater(() -> {
                    sideBySideBtn.setDisable(false);
                    publishStatus(out.getMessage());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    sideBySideBtn.setDisable(false);
                    publishStatus("Side-by-side snap failed: " + ex.getMessage());
                });
            }
        });
    }

    private void startWatcherIfNeeded() {
        if (!WindowsWindowDocker.isWindows() || !hostVisible) {
            return;
        }
        watcherTimeline.play();
    }

    private void pauseWatcher() {
        watcherTimeline.stop();
        clearHoverHighlight();
    }

    private void pollNativeWindowStateAsync() {
        if (!hostVisible || !WindowsWindowDocker.isWindows() || getScene() == null || getScene().getWindow() == null) {
            return;
        }
        if (!getScene().getWindow().isShowing()) {
            return;
        }
        if (!pollInFlight.compareAndSet(false, true)) {
            return;
        }

        WindowsWindowSnapper.Rect hostRect = computeCurrentHostPhysicalRect();
        boolean oneNoteOnly = !acceptAnyWindowCheck.isSelected();
        long ownPid = ProcessHandle.current().pid();
        WindowsWindowDocker.DockedSession currentSession = activeSession;
        WindowsWindowSnapper.Rect expectedRect = lastSyncedTargetRect;

        AppExecutors.ioExecutor().execute(() -> {
            try {
                if (currentSession != null && currentSession.isActive() && !hostDetached) {
                    if (!WindowsWindowDocker.isWindowAlive(currentSession.getHwnd())) {
                        Platform.runLater(() -> {
                            if (activeSession == currentSession) {
                                activeSession = null;
                                lastSyncedTargetRect = null;
                                updateHostUiState();
                                publishStatus("Docked window was closed.");
                            }
                        });
                        return;
                    }
                    if (!WindowsWindowDocker.isLeftMouseButtonPressed() && expectedRect != null) {
                        WindowsWindowSnapper.Rect actualVisible = WindowsWindowDocker.readVisibleBounds(currentSession.getHwnd());
                        if (WindowsWindowDocker.hasMovedOutsideThreshold(actualVisible, expectedRect, DRAG_OUT_THRESHOLD_PX)) {
                            WindowsWindowDocker.undock(currentSession, false);
                            Platform.runLater(() -> {
                                if (activeSession == currentSession) {
                                    activeSession = null;
                                    lastSyncedTargetRect = null;
                                    updateHostUiState();
                                    publishStatus("Detached " + currentSession.getTitle() + " after dragging away.");
                                    refreshWindowListAsync();
                                }
                            });
                        }
                    }
                    return;
                }

                if (currentSession == null && hostRect != null) {
                    WindowsWindowDocker.DragProbe probe = WindowsWindowDocker.pollDraggedExternalWindow(
                            ownPid,
                            hostRect,
                            oneNoteOnly
                    );
                    Platform.runLater(() -> handleDragInProbe(probe));
                }
            } finally {
                pollInFlight.set(false);
            }
        });
    }

    private void handleDragInProbe(WindowsWindowDocker.DragProbe probe) {
        if (activeSession != null || !hostVisible) {
            clearHoverHighlight();
            return;
        }
        if (probe.isLeftButtonDown() && probe.isOverZone() && probe.getHwnd() != null) {
            hoveringDropZone = true;
            hoveredDragHwnd = probe.getHwnd();
            hoveredDragTitle = probe.getTitle();
            if (!dockHost.getStyleClass().contains("workspace-dock-hover")) {
                dockHost.getStyleClass().add("workspace-dock-hover");
            }
            dropTitleLabel.setText("Release mouse to snap inside this pane:");
            dropSubtitleLabel.setText("\"" + hoveredDragTitle + "\"");
        } else if (!probe.isLeftButtonDown() && hoveringDropZone && hoveredDragHwnd != null) {
            Pointer targetHwnd = hoveredDragHwnd;
            String title = hoveredDragTitle;
            clearHoverHighlight();
            dockTargetWindow(targetHwnd, title);
        } else if (!probe.isOverZone()) {
            clearHoverHighlight();
        }
    }

    private void clearHoverHighlight() {
        hoveringDropZone = false;
        hoveredDragHwnd = null;
        hoveredDragTitle = "";
        dockHost.getStyleClass().remove("workspace-dock-hover");
        if (activeSession == null) {
            dropTitleLabel.setText("Drag OneNote (or any app/browser tab) here to snap inside");
            dropSubtitleLabel.setText(
                    "Release the window over this pane to lock it flush beside your lectures and calendar.\n"
                            + "You can draw with your pen in OneNote and scroll PDFs on the left at the same time."
            );
        }
    }

    public void requestCoalescedBoundsSync() {
        if (activeSession != null && activeSession.isActive() && hostVisible && !hostDetached) {
            syncDebounce.playFromStart();
        }
    }

    private void syncDockedBoundsNow() {
        WindowsWindowDocker.DockedSession session = activeSession;
        if (session == null || !session.isActive() || !hostVisible || hostDetached) {
            return;
        }
        WindowsWindowSnapper.Rect targetRect = computeCurrentHostPhysicalRect();
        if (targetRect == null) {
            return;
        }
        lastSyncedTargetRect = targetRect;
        if (!syncInFlight.compareAndSet(false, true)) {
            return;
        }
        AppExecutors.ioExecutor().execute(() -> {
            try {
                WindowsWindowDocker.syncBounds(session, targetRect);
            } finally {
                syncInFlight.set(false);
            }
        });
    }

    private WindowsWindowSnapper.Rect computeCurrentHostPhysicalRect() {
        Scene scene = dockHost.getScene();
        if (scene == null || scene.getWindow() == null || !scene.getWindow().isShowing()) {
            return null;
        }
        if (dockHost.getWidth() < 80 || dockHost.getHeight() < 80) {
            return null;
        }
        Bounds boundsInScene = dockHost.localToScene(dockHost.getBoundsInLocal());
        if (boundsInScene == null || boundsInScene.getWidth() < 80 || boundsInScene.getHeight() < 80) {
            return null;
        }
        Window win = scene.getWindow();
        double scaleX = 1.0;
        double scaleY = 1.0;
        if (win instanceof Stage) {
            scaleX = Math.max(0.5, ((Stage) win).getRenderScaleX());
            scaleY = Math.max(0.5, ((Stage) win).getRenderScaleY());
        }
        long ownPid = ProcessHandle.current().pid();
        Pointer appHwnd = WindowsWindowDocker.findAppWindow(ownPid);
        int originX;
        int originY;
        if (appHwnd != null) {
            int[] pt = WindowsWindowDocker.getClientOriginScreen(appHwnd);
            originX = pt[0];
            originY = pt[1];
        } else {
            originX = (int) Math.round((win.getX() + scene.getX()) * scaleX);
            originY = (int) Math.round((win.getY() + scene.getY()) * scaleY);
        }
        return WindowsWindowDocker.computeHostPhysicalRect(
                originX,
                originY,
                boundsInScene.getMinX(),
                boundsInScene.getMinY(),
                boundsInScene.getWidth(),
                boundsInScene.getHeight(),
                scaleX,
                scaleY
        );
    }

    private void updateHostUiState() {
        boolean docked = activeSession != null && activeSession.isActive();
        undockBtn.setDisable(!docked);
        dropCard.setVisible(!docked);
        dropCard.setManaged(!docked);
    }

    public void onHostHidden() {
        hostVisible = false;
        pauseWatcher();
        WindowsWindowDocker.DockedSession session = activeSession;
        if (session != null && session.isActive() && !hostDetached) {
            hostDetached = true;
            long ownPid = ProcessHandle.current().pid();
            AppExecutors.ioExecutor().execute(() -> {
                Pointer appHwnd = WindowsWindowDocker.findAppWindow(ownPid);
                WindowsWindowDocker.detachTemporarily(session, appHwnd);
            });
        }
    }

    public void onHostShown() {
        hostVisible = true;
        reattachActiveSession();
        startWatcherIfNeeded();
        refreshWindowListAsync();
    }

    private void reattachActiveSession() {
        WindowsWindowDocker.DockedSession session = activeSession;
        if (session == null || !session.isActive()) {
            hostDetached = false;
            updateHostUiState();
            return;
        }
        if (!hostDetached) {
            requestCoalescedBoundsSync();
            return;
        }
        WindowsWindowSnapper.Rect targetRect = computeCurrentHostPhysicalRect();
        if (targetRect == null) {
            return;
        }
        lastSyncedTargetRect = targetRect;
        long ownPid = ProcessHandle.current().pid();
        AppExecutors.ioExecutor().execute(() -> {
            Pointer appHwnd = WindowsWindowDocker.findAppWindow(ownPid);
            boolean ok = WindowsWindowDocker.reattach(session, appHwnd, targetRect);
            Platform.runLater(() -> {
                hostDetached = !ok;
                if (!ok) {
                    activeSession = null;
                    lastSyncedTargetRect = null;
                }
                updateHostUiState();
            });
        });
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
        refreshWindowListAsync();
    }

    @Override
    public void flushPendingEdits() {
    }

    @Override
    public void releaseResources() {
        hostVisible = false;
        pauseWatcher();
        syncDebounce.stop();
        WindowsWindowDocker.DockedSession session = activeSession;
        activeSession = null;
        lastSyncedTargetRect = null;
        hostDetached = false;
        updateHostUiState();
        if (session != null) {
            AppExecutors.ioExecutor().execute(() -> WindowsWindowDocker.undock(session, true));
        }
    }

    @Override
    public void setActiveTheme(UiTheme resolvedTheme) {
    }
}
