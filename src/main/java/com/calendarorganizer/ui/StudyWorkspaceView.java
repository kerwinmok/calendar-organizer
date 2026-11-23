package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseFilePreviewer;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.service.AppExecutors;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.workspace.WorkspaceLayoutConfig;
import com.calendarorganizer.workspace.WorkspacePaneType;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.animation.Animation;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class StudyWorkspaceView extends BorderPane {

    private static final DataFormat PANE_DRAG_FORMAT = resolvePaneDragFormat();

    private final CourseManager courseManager;
    private final CourseFilePreviewer previewer;
    private final CalendarService calendarService;
    private final Supplier<Course> preferredCourseSupplier;
    private final Consumer<String> onLayoutSaved;

    private final ComboBox<WorkspaceLayoutConfig.LayoutMode> layoutModeBox = new ComboBox<>();
    private final MenuButton addPaneBtn = new MenuButton("+ Add Pane");
    private final Button studyTrioBtn = new Button("PDF + Notes + Calendar");
    private final Button resetLayoutBtn = new Button("Reset");
    private final Label paneCountLabel = new Label();
    private final Button fullScreenBtn = new Button("Full Screen");
    private final StackPane splitContainer = new StackPane();

    private final List<PaneSlot> slots = new ArrayList<>();
    private final PauseTransition saveDebounce = new PauseTransition(Duration.millis(400));
    private final AtomicLong coursesLoadSeq = new AtomicLong(0L);

    private WorkspaceLayoutConfig layoutConfig;
    private SplitPane primarySplit;
    private SplitPane firstGroupSplit;
    private SplitPane secondGroupSplit;
    private List<Course> cachedCourses = List.of();
    private UiTheme resolvedTheme = UiTheme.CHARCOAL;
    private Consumer<Boolean> onFullScreenToggle;
    private Consumer<String> onStatusChanged;
    private Runnable onCalendarDataChanged;
    private boolean fullScreenState;
    private boolean suppressingLayoutEvents;

    private static DataFormat resolvePaneDragFormat() {
        DataFormat existing = DataFormat.lookupMimeType("application/x-calendarorganizer-pane");
        if (existing != null) {
            return existing;
        }
        return new DataFormat("application/x-calendarorganizer-pane");
    }

    private static final class PaneSlot {
        final WorkspaceLayoutConfig.PaneState state;
        final BorderPane frame;
        final ComboBox<WorkspacePaneType> typeBox;
        final Button closeBtn;
        WorkspacePaneContent content;

        PaneSlot(
                WorkspaceLayoutConfig.PaneState state,
                BorderPane frame,
                ComboBox<WorkspacePaneType> typeBox,
                Button closeBtn,
                WorkspacePaneContent content
        ) {
            this.state = state;
            this.frame = frame;
            this.typeBox = typeBox;
            this.closeBtn = closeBtn;
            this.content = content;
        }
    }

    public StudyWorkspaceView(
            CourseManager courseManager,
            CourseFilePreviewer previewer,
            CalendarService calendarService,
            String savedLayoutJson,
            Supplier<Course> preferredCourseSupplier,
            Consumer<String> onLayoutSaved
    ) {
        this.courseManager = courseManager;
        this.previewer = previewer;
        this.calendarService = calendarService;
        this.preferredCourseSupplier = preferredCourseSupplier;
        this.onLayoutSaved = onLayoutSaved;
        this.layoutConfig = WorkspaceLayoutConfig.fromJson(savedLayoutJson);

        saveDebounce.setOnFinished(e -> persistCurrentLayout());

        setTop(buildToolbar());
        setCenter(splitContainer);

        initializeFromConfig();
        refreshCoursesAsync();
    }

    private HBox buildToolbar() {
        Label layoutLbl = new Label("Layout");
        layoutLbl.getStyleClass().add("dim-label");

        layoutModeBox.getItems().setAll(WorkspaceLayoutConfig.LayoutMode.values());
        layoutModeBox.setValue(layoutConfig.getLayoutMode());
        layoutModeBox.setOnAction(e -> {
            if (suppressingLayoutEvents) {
                return;
            }
            WorkspaceLayoutConfig.LayoutMode chosen = layoutModeBox.getValue();
            if (chosen != null && chosen != layoutConfig.getLayoutMode()) {
                captureLiveDividersIntoConfig();
                layoutConfig.setLayoutMode(chosen);
                rebuildLayoutGraph();
                scheduleLayoutSave();
            }
        });

        for (WorkspacePaneType type : WorkspacePaneType.values()) {
            MenuItem item = new MenuItem(type.getDisplayName());
            item.setOnAction(e -> addPane(type));
            addPaneBtn.getItems().add(item);
        }

        studyTrioBtn.setOnAction(e -> applyStudyTrioLayout());
        resetLayoutBtn.setOnAction(e -> resetToDefaultLayout());
        paneCountLabel.getStyleClass().add("dim-label");

        fullScreenBtn.setMinWidth(Region.USE_PREF_SIZE);
        fullScreenBtn.setOnAction(e -> {
            if (onFullScreenToggle != null) {
                onFullScreenToggle.accept(!fullScreenState);
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(
                8,
                layoutLbl,
                layoutModeBox,
                addPaneBtn,
                studyTrioBtn,
                resetLayoutBtn,
                paneCountLabel,
                spacer,
                fullScreenBtn
        );
        toolbar.getStyleClass().add("workspace-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        return toolbar;
    }

    private void initializeFromConfig() {
        suppressingLayoutEvents = true;
        try {
            clearAllSlotContents();
            slots.clear();
            for (WorkspaceLayoutConfig.PaneState state : layoutConfig.getPanes()) {
                slots.add(buildSlot(state.copy()));
            }
            layoutModeBox.setValue(layoutConfig.getLayoutMode());
            rebuildLayoutGraph();
            updateToolbarControls();
        } finally {
            suppressingLayoutEvents = false;
        }
    }

    private PaneSlot buildSlot(WorkspaceLayoutConfig.PaneState state) {
        Label gripLabel = new Label("⋮⋮");
        gripLabel.getStyleClass().add("workspace-drag-grip");

        ComboBox<WorkspacePaneType> typeBox = new ComboBox<>();
        typeBox.getItems().setAll(WorkspacePaneType.values());
        typeBox.setValue(state.getType());

        Button closeBtn = new Button("×");
        closeBtn.getStyleClass().add("workspace-close-btn");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(6, gripLabel, typeBox, spacer, closeBtn);
        header.getStyleClass().add("workspace-pane-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(5, 8, 5, 8));

        BorderPane frame = new BorderPane();
        frame.getStyleClass().add("workspace-pane");
        frame.setTop(header);

        WorkspacePaneContent content = createPaneContent(state);
        frame.setCenter(content.getView());
        content.setActiveTheme(resolvedTheme);

        PaneSlot slot = new PaneSlot(state, frame, typeBox, closeBtn, content);

        typeBox.setOnAction(e -> {
            if (suppressingLayoutEvents) {
                return;
            }
            WorkspacePaneType chosen = typeBox.getValue();
            if (chosen != null && chosen != slot.state.getType()) {
                swapPaneType(slot, chosen);
            }
        });
        closeBtn.setOnAction(e -> closePane(slot));
        installSlotDragAndDrop(slot, header);
        return slot;
    }

    private void installSlotDragAndDrop(PaneSlot slot, HBox header) {
        header.setOnDragDetected(ev -> {
            int sourceIndex = slots.indexOf(slot);
            if (sourceIndex < 0) {
                return;
            }
            Dragboard db = header.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent clip = new ClipboardContent();
            clip.put(PANE_DRAG_FORMAT, Integer.toString(sourceIndex));
            db.setContent(clip);
            ev.consume();
        });

        slot.frame.setOnDragOver(ev -> {
            Dragboard db = ev.getDragboard();
            if (db.hasContent(PANE_DRAG_FORMAT)) {
                String raw = String.valueOf(db.getContent(PANE_DRAG_FORMAT));
                int sourceIndex = parseIndex(raw);
                int targetIndex = slots.indexOf(slot);
                if (sourceIndex >= 0 && targetIndex >= 0 && sourceIndex != targetIndex) {
                    ev.acceptTransferModes(TransferMode.MOVE);
                    if (!slot.frame.getStyleClass().contains("workspace-drop-target")) {
                        slot.frame.getStyleClass().add("workspace-drop-target");
                    }
                }
            }
            ev.consume();
        });

        slot.frame.setOnDragExited(ev -> {
            slot.frame.getStyleClass().remove("workspace-drop-target");
            ev.consume();
        });

        slot.frame.setOnDragDropped(ev -> {
            slot.frame.getStyleClass().remove("workspace-drop-target");
            boolean success = false;
            Dragboard db = ev.getDragboard();
            if (db.hasContent(PANE_DRAG_FORMAT)) {
                int sourceIndex = parseIndex(String.valueOf(db.getContent(PANE_DRAG_FORMAT)));
                int targetIndex = slots.indexOf(slot);
                if (sourceIndex >= 0 && targetIndex >= 0 && sourceIndex != targetIndex) {
                    swapSlotPositions(sourceIndex, targetIndex);
                    success = true;
                }
            }
            ev.setDropCompleted(success);
            ev.consume();
        });

        header.setOnDragDone(ev -> {
            for (PaneSlot candidate : slots) {
                candidate.frame.getStyleClass().remove("workspace-drop-target");
            }
            ev.consume();
        });
    }

    private static int parseIndex(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void swapSlotPositions(int sourceIndex, int targetIndex) {
        captureLiveDividersIntoConfig();
        Collections.swap(slots, sourceIndex, targetIndex);
        rebuildLayoutGraph();
        scheduleLayoutSave();
        publishStatus("Swapped workspace pane positions.");
    }

    private WorkspacePaneContent createPaneContent(WorkspaceLayoutConfig.PaneState state) {
        WorkspacePaneType type = state.getType();
        if (type == WorkspacePaneType.NOTES) {
            return new NotesWorkspacePane(
                    courseManager,
                    state,
                    this::publishStatus,
                    this::scheduleLayoutSave
            );
        }
        if (type == WorkspacePaneType.CALENDAR) {
            return new CalendarWorkspacePane(
                    calendarService,
                    this::publishStatus,
                    () -> {
                        if (onCalendarDataChanged != null) {
                            onCalendarDataChanged.run();
                        }
                    }
            );
        }
        LectureWorkspacePane lecturePane = new LectureWorkspacePane(
                courseManager,
                previewer,
                state,
                toggle -> {
                    if (onFullScreenToggle != null) {
                        onFullScreenToggle.accept(toggle);
                    }
                },
                () -> closePaneByState(state),
                this::publishStatus,
                this::scheduleLayoutSave
        );
        lecturePane.setFullScreenState(fullScreenState);
        return lecturePane;
    }

    private void addPane(WorkspacePaneType type) {
        if (slots.size() >= WorkspaceLayoutConfig.MAX_PANES) {
            publishStatus("Workspace supports up to 4 panes.");
            return;
        }
        captureLiveDividersIntoConfig();
        WorkspaceLayoutConfig.PaneState newState = new WorkspaceLayoutConfig.PaneState(type);
        PaneSlot slot = buildSlot(newState);
        slots.add(slot);
        slot.content.refreshCourses(cachedCourses, preferredCourse());
        rebuildLayoutGraph();
        updateToolbarControls();
        scheduleLayoutSave();
        publishStatus("Added " + type.getDisplayName() + " pane.");
    }

    private void swapPaneType(PaneSlot slot, WorkspacePaneType nextType) {
        slot.content.flushPendingEdits();
        slot.content.releaseResources();
        slot.state.setType(nextType);
        slot.content = createPaneContent(slot.state);
        slot.frame.setCenter(slot.content.getView());
        slot.content.setActiveTheme(resolvedTheme);
        slot.content.refreshCourses(cachedCourses, preferredCourse());
        scheduleLayoutSave();
        publishStatus("Swapped pane to " + nextType.getDisplayName() + ".");
    }

    private void closePaneByState(WorkspaceLayoutConfig.PaneState targetState) {
        for (PaneSlot slot : slots) {
            if (slot.state == targetState) {
                closePane(slot);
                return;
            }
        }
    }

    private void closePane(PaneSlot slot) {
        if (slots.size() <= WorkspaceLayoutConfig.MIN_PANES) {
            publishStatus("Workspace requires at least 2 panes.");
            return;
        }
        captureLiveDividersIntoConfig();
        slot.content.flushPendingEdits();
        slot.content.releaseResources();
        slots.remove(slot);
        rebuildLayoutGraph();
        updateToolbarControls();
        scheduleLayoutSave();
        publishStatus("Closed pane.");
    }

    private void applyStudyTrioLayout() {
        flushPendingEdits();
        layoutConfig = WorkspaceLayoutConfig.studyTrioConfig();
        initializeFromConfig();
        applyCoursesToAllSlots();
        scheduleLayoutSave();
        publishStatus("Loaded Study Trio: Lecture Viewer + Notes Editor + Calendar.");
    }

    private void resetToDefaultLayout() {
        flushPendingEdits();
        layoutConfig = WorkspaceLayoutConfig.defaultConfig();
        initializeFromConfig();
        applyCoursesToAllSlots();
        scheduleLayoutSave();
        publishStatus("Workspace layout reset to default.");
    }

    private void rebuildLayoutGraph() {
        suppressingLayoutEvents = true;
        try {
            primarySplit = null;
            firstGroupSplit = null;
            secondGroupSplit = null;
            splitContainer.getChildren().clear();

            if (slots.isEmpty()) {
                return;
            }

            WorkspaceLayoutConfig.LayoutMode mode = layoutConfig.getLayoutMode();
            int count = slots.size();

            if (mode == WorkspaceLayoutConfig.LayoutMode.HORIZONTAL) {
                primarySplit = createSplit(Orientation.HORIZONTAL, extractSlotNodes(0, count));
                applyDividerPositions(primarySplit, layoutConfig.getPrimaryDividers());
                attachDividerListeners(primarySplit);
                splitContainer.getChildren().setAll(primarySplit);
            } else if (mode == WorkspaceLayoutConfig.LayoutMode.VERTICAL) {
                primarySplit = createSplit(Orientation.VERTICAL, extractSlotNodes(0, count));
                applyDividerPositions(primarySplit, layoutConfig.getPrimaryDividers());
                attachDividerListeners(primarySplit);
                splitContainer.getChildren().setAll(primarySplit);
            } else if (count == 2) {
                primarySplit = createSplit(Orientation.HORIZONTAL, slots.get(0).frame, slots.get(1).frame);
                applyDividerPositions(primarySplit, layoutConfig.getPrimaryDividers());
                attachDividerListeners(primarySplit);
                splitContainer.getChildren().setAll(primarySplit);
            } else if (count == 3) {
                firstGroupSplit = createSplit(Orientation.VERTICAL, slots.get(0).frame, slots.get(1).frame);
                primarySplit = createSplit(Orientation.HORIZONTAL, firstGroupSplit, slots.get(2).frame);
                applyDividerPositions(primarySplit, layoutConfig.getPrimaryDividers());
                applyDividerPositions(firstGroupSplit, layoutConfig.getFirstGroupDividers());
                attachDividerListeners(primarySplit);
                attachDividerListeners(firstGroupSplit);
                splitContainer.getChildren().setAll(primarySplit);
            } else {
                firstGroupSplit = createSplit(Orientation.HORIZONTAL, slots.get(0).frame, slots.get(1).frame);
                secondGroupSplit = createSplit(Orientation.HORIZONTAL, slots.get(2).frame, slots.get(3).frame);
                primarySplit = createSplit(Orientation.VERTICAL, firstGroupSplit, secondGroupSplit);
                applyDividerPositions(primarySplit, layoutConfig.getPrimaryDividers());
                applyDividerPositions(firstGroupSplit, layoutConfig.getFirstGroupDividers());
                applyDividerPositions(secondGroupSplit, layoutConfig.getSecondGroupDividers());
                attachDividerListeners(primarySplit);
                attachDividerListeners(firstGroupSplit);
                attachDividerListeners(secondGroupSplit);
                splitContainer.getChildren().setAll(primarySplit);
            }

            // Re-apply after CSS/layout pass so saved ratios are not overwritten by default 0.5 skin layout.
            Platform.runLater(() -> {
                suppressingLayoutEvents = true;
                try {
                    applyDividerPositions(primarySplit, layoutConfig.getPrimaryDividers());
                    applyDividerPositions(firstGroupSplit, layoutConfig.getFirstGroupDividers());
                    applyDividerPositions(secondGroupSplit, layoutConfig.getSecondGroupDividers());
                } finally {
                    suppressingLayoutEvents = false;
                }
            });
        } finally {
            suppressingLayoutEvents = false;
        }
    }

    private Node[] extractSlotNodes(int fromIndex, int toExclusive) {
        Node[] nodes = new Node[toExclusive - fromIndex];
        for (int i = fromIndex; i < toExclusive; i++) {
            nodes[i - fromIndex] = slots.get(i).frame;
        }
        return nodes;
    }

    private SplitPane createSplit(Orientation orientation, Node... children) {
        SplitPane split = new SplitPane(children);
        split.setOrientation(orientation);
        return split;
    }

    private void applyDividerPositions(SplitPane split, List<Double> saved) {
        if (split == null) {
            return;
        }
        int dividerCount = split.getDividers().size();
        if (dividerCount <= 0) {
            return;
        }
        if (saved != null && saved.size() == dividerCount) {
            double[] arr = new double[dividerCount];
            for (int i = 0; i < dividerCount; i++) {
                arr[i] = saved.get(i);
            }
            split.setDividerPositions(arr);
            return;
        }
        double[] even = new double[dividerCount];
        for (int i = 0; i < dividerCount; i++) {
            even[i] = (i + 1.0) / (dividerCount + 1.0);
        }
        split.setDividerPositions(even);
    }

    private void attachDividerListeners(SplitPane split) {
        if (split == null) {
            return;
        }
        for (SplitPane.Divider divider : split.getDividers()) {
            divider.positionProperty().addListener((obs, oldVal, newVal) -> {
                if (!suppressingLayoutEvents) {
                    scheduleLayoutSave();
                }
            });
        }
    }

    private void captureLiveDividersIntoConfig() {
        layoutConfig.setPrimaryDividers(readDividers(primarySplit));
        layoutConfig.setFirstGroupDividers(readDividers(firstGroupSplit));
        layoutConfig.setSecondGroupDividers(readDividers(secondGroupSplit));
    }

    private static List<Double> readDividers(SplitPane split) {
        List<Double> positions = new ArrayList<>();
        if (split == null) {
            return positions;
        }
        for (SplitPane.Divider divider : split.getDividers()) {
            positions.add(divider.getPosition());
        }
        return positions;
    }

    private void updateToolbarControls() {
        boolean atMax = slots.size() >= WorkspaceLayoutConfig.MAX_PANES;
        boolean atMin = slots.size() <= WorkspaceLayoutConfig.MIN_PANES;
        addPaneBtn.setDisable(atMax);
        paneCountLabel.setText(slots.size() + " / " + WorkspaceLayoutConfig.MAX_PANES + " panes");
        for (PaneSlot slot : slots) {
            slot.closeBtn.setDisable(atMin);
        }
    }

    private void scheduleLayoutSave() {
        saveDebounce.playFromStart();
    }

    private void persistCurrentLayout() {
        captureLiveDividersIntoConfig();
        List<WorkspaceLayoutConfig.PaneState> states = new ArrayList<>();
        for (PaneSlot slot : slots) {
            states.add(slot.state.copy());
        }
        layoutConfig.setPanes(states);
        String json = layoutConfig.toJson();
        if (onLayoutSaved != null) {
            onLayoutSaved.accept(json);
        }
    }

    public void refreshCoursesAsync() {
        long seq = coursesLoadSeq.incrementAndGet();
        AppExecutors.ioExecutor().execute(() -> {
            try {
                List<Course> loaded = courseManager.listCourses();
                Platform.runLater(() -> {
                    if (coursesLoadSeq.get() != seq) {
                        return;
                    }
                    cachedCourses = loaded;
                    applyCoursesToAllSlots();
                });
            } catch (java.io.IOException ex) {
                Platform.runLater(() -> publishStatus("Could not load courses: " + ex.getMessage()));
            }
        });
    }

    private void applyCoursesToAllSlots() {
        Course preferred = preferredCourse();
        for (PaneSlot slot : slots) {
            slot.content.refreshCourses(cachedCourses, preferred);
        }
    }

    private Course preferredCourse() {
        if (preferredCourseSupplier != null) {
            return preferredCourseSupplier.get();
        }
        return null;
    }

    public void setFullScreenState(boolean fullScreen) {
        this.fullScreenState = fullScreen;
        if (fullScreen) {
            fullScreenBtn.setText("Exit Full Screen");
        } else {
            fullScreenBtn.setText("Full Screen");
        }
        for (PaneSlot slot : slots) {
            if (slot.content instanceof LectureWorkspacePane) {
                ((LectureWorkspacePane) slot.content).setFullScreenState(fullScreen);
            }
        }
    }

    public void setOnFullScreenToggle(Consumer<Boolean> listener) {
        this.onFullScreenToggle = listener;
    }

    public void setOnStatusChanged(Consumer<String> listener) {
        this.onStatusChanged = listener;
    }

    public void setOnCalendarDataChanged(Runnable listener) {
        this.onCalendarDataChanged = listener;
    }

    public void setActiveTheme(UiTheme resolved) {
        if (resolved == null) {
            return;
        }
        this.resolvedTheme = resolved;
        for (PaneSlot slot : slots) {
            slot.content.setActiveTheme(resolved);
        }
    }

    public void flushPendingEdits() {
        if (saveDebounce.getStatus() == Animation.Status.RUNNING) {
            saveDebounce.stop();
            persistCurrentLayout();
        }
        for (PaneSlot slot : slots) {
            slot.content.flushPendingEdits();
        }
    }

    public void onTabActivated() {
        refreshCoursesAsync();
        for (PaneSlot slot : slots) {
            if (slot.content instanceof CalendarWorkspacePane) {
                ((CalendarWorkspacePane) slot.content).refreshCalendar();
            }
        }
    }

    public void onTabDeactivated() {
        flushPendingEdits();
        for (PaneSlot slot : slots) {
            slot.content.releaseResources();
        }
    }

    public void releaseTransientCaches() {
        saveDebounce.stop();
        persistCurrentLayout();
        for (PaneSlot slot : slots) {
            slot.content.flushPendingEdits();
            slot.content.releaseResources();
        }
    }

    public void shutdown() {
        releaseTransientCaches();
    }

    private void clearAllSlotContents() {
        for (PaneSlot slot : slots) {
            slot.content.flushPendingEdits();
            slot.content.releaseResources();
        }
    }

    private void publishStatus(String message) {
        if (onStatusChanged != null && message != null && !message.isBlank()) {
            onStatusChanged.accept(message);
        }
    }
}
