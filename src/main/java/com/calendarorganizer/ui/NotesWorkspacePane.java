package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseItem;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.course.CourseSection;
import com.calendarorganizer.course.CourseSortOrder;
import com.calendarorganizer.service.AppExecutors;
import com.calendarorganizer.workspace.WorkspaceLayoutConfig;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class NotesWorkspacePane extends BorderPane implements WorkspacePaneContent {

    private final CourseManager courseManager;
    private final WorkspaceLayoutConfig.PaneState paneState;
    private final Consumer<String> onStatusChanged;
    private final Runnable onLayoutStateChanged;

    private final ComboBox<Course> courseBox = new ComboBox<>();
    private final ComboBox<CourseSection> sectionBox = new ComboBox<>();
    private final ComboBox<CourseItem> noteBox = new ComboBox<>();
    private final Button newNoteBtn = new Button("+ New");
    private final Button saveNowBtn = new Button("Save");
    private final Label saveStateLabel = new Label("No note");
    private final TextArea editor = new TextArea();

    private final PauseTransition autosaveTimer = new PauseTransition(Duration.millis(1200));
    private final AtomicLong itemLoadSeq = new AtomicLong(0L);

    private UiTheme resolvedTheme = UiTheme.CHARCOAL;
    private CourseItem activeItem;
    private boolean updatingSelectors;
    private boolean loadingNote;
    private boolean dirty;

    public NotesWorkspacePane(
            CourseManager courseManager,
            WorkspaceLayoutConfig.PaneState paneState,
            Consumer<String> onStatusChanged,
            Runnable onLayoutStateChanged
    ) {
        this.courseManager = courseManager;
        this.paneState = paneState;
        this.onStatusChanged = onStatusChanged;
        this.onLayoutStateChanged = onLayoutStateChanged;

        configureSelectors();
        configureEditor();

        saveStateLabel.getStyleClass().add("dim-label");
        newNoteBtn.setOnAction(e -> handleCreateNewNote());
        saveNowBtn.setOnAction(e -> saveActiveNoteAsync());
        saveNowBtn.setDisable(true);
        newNoteBtn.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox subBar = new HBox(6, courseBox, sectionBox, noteBox, newNoteBtn, saveNowBtn, spacer, saveStateLabel);
        subBar.getStyleClass().add("workspace-subbar");
        subBar.setAlignment(Pos.CENTER_LEFT);
        subBar.setPadding(new Insets(6, 10, 6, 10));

        setTop(subBar);
        setCenter(editor);
    }

    private void configureSelectors() {
        WorkspaceUiHelpers.configureCourseBox(courseBox, "Course...", 135);
        courseBox.setOnAction(e -> {
            if (updatingSelectors) {
                return;
            }
            flushPendingEdits();
            Course selected = courseBox.getValue();
            if (selected != null) {
                paneState.setTargetCourse(selected.getFolderName());
                if (onLayoutStateChanged != null) {
                    onLayoutStateChanged.run();
                }
                newNoteBtn.setDisable(false);
                reloadMarkdownListAsync(null);
            } else {
                newNoteBtn.setDisable(true);
                clearNoteEditor();
            }
        });

        sectionBox.getItems().setAll(CourseSection.values());
        sectionBox.setValue(CourseSection.NOTES);
        sectionBox.setPrefWidth(105);
        sectionBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(CourseSection item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                } else {
                    setText(item.getLabel());
                }
            }
        });
        sectionBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(CourseSection item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("Section");
                } else {
                    setText(item.getLabel());
                }
            }
        });
        sectionBox.setOnAction(e -> {
            if (updatingSelectors) {
                return;
            }
            flushPendingEdits();
            reloadMarkdownListAsync(null);
        });

        noteBox.setPrefWidth(160);
        noteBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(CourseItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("");
                } else {
                    setText(item.getFileName());
                }
            }
        });
        noteBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(CourseItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("No .md note");
                } else {
                    setText(item.getFileName());
                }
            }
        });
        noteBox.setOnAction(e -> {
            if (updatingSelectors) {
                return;
            }
            flushPendingEdits();
            loadNoteIntoEditorAsync(noteBox.getValue());
        });
    }

    private void configureEditor() {
        editor.getStyleClass().add("workspace-editor");
        editor.setWrapText(true);
        editor.setDisable(true);
        editor.setPromptText("Select a course markdown note or click + New to start writing...");

        autosaveTimer.setOnFinished(e -> saveActiveNoteAsync());
        editor.textProperty().addListener((obs, oldVal, newVal) -> {
            if (loadingNote || activeItem == null) {
                return;
            }
            dirty = true;
            saveNowBtn.setDisable(false);
            saveStateLabel.setText("Unsaved");
            autosaveTimer.playFromStart();
        });
    }

    private void reloadMarkdownListAsync(String selectFileName) {
        Course course = courseBox.getValue();
        CourseSection section = sectionBox.getValue();
        if (course == null || section == null) {
            clearNoteEditor();
            return;
        }
        long seq = itemLoadSeq.incrementAndGet();
        AppExecutors.ioExecutor().execute(() -> {
            try {
                List<CourseItem> items = courseManager.listItems(course, section, CourseSortOrder.NAME_ASC);
                List<CourseItem> markdownOnly = new ArrayList<>();
                for (CourseItem item : items) {
                    if (item.isMarkdown()) {
                        markdownOnly.add(item);
                    }
                }
                Platform.runLater(() -> {
                    if (itemLoadSeq.get() != seq) {
                        return;
                    }
                    updatingSelectors = true;
                    try {
                        noteBox.getItems().setAll(markdownOnly);
                        CourseItem target = pickTargetNote(markdownOnly, selectFileName);
                        noteBox.setValue(target);
                    } finally {
                        updatingSelectors = false;
                    }
                    loadNoteIntoEditorAsync(noteBox.getValue());
                });
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    saveStateLabel.setText("Error");
                    publishStatus("Could not list course notes: " + ex.getMessage());
                });
            }
        });
    }

    private CourseItem pickTargetNote(List<CourseItem> items, String preferredFileName) {
        if (items.isEmpty()) {
            return null;
        }
        if (preferredFileName != null && !preferredFileName.isBlank()) {
            for (CourseItem item : items) {
                if (preferredFileName.equalsIgnoreCase(item.getFileName())) {
                    return item;
                }
            }
        }
        if (activeItem != null) {
            for (CourseItem item : items) {
                if (activeItem.getFilePath().equals(item.getFilePath())) {
                    return item;
                }
            }
        }
        return items.get(0);
    }

    private void loadNoteIntoEditorAsync(CourseItem item) {
        if (item == null) {
            clearNoteEditor();
            return;
        }
        long seq = itemLoadSeq.incrementAndGet();
        AppExecutors.ioExecutor().execute(() -> {
            try {
                String content = courseManager.readItem(item);
                Platform.runLater(() -> {
                    if (itemLoadSeq.get() != seq) {
                        return;
                    }
                    loadingNote = true;
                    try {
                        activeItem = item;
                        editor.setDisable(false);
                        editor.setText(content);
                        dirty = false;
                        saveNowBtn.setDisable(true);
                        saveStateLabel.setText("Saved");
                    } finally {
                        loadingNote = false;
                    }
                });
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    saveStateLabel.setText("Load failed");
                    publishStatus("Failed to read note: " + ex.getMessage());
                });
            }
        });
    }

    private void clearNoteEditor() {
        autosaveTimer.stop();
        loadingNote = true;
        try {
            activeItem = null;
            dirty = false;
            saveNowBtn.setDisable(true);
            editor.clear();
            editor.setDisable(true);
            saveStateLabel.setText("No note");
        } finally {
            loadingNote = false;
        }
    }

    private void handleCreateNewNote() {
        Course course = courseBox.getValue();
        CourseSection section = sectionBox.getValue();
        if (course == null || section == null) {
            return;
        }
        flushPendingEdits();

        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Study Note");
        dialog.setHeaderText("Create Markdown Note in " + course.getDisplayName() + " / " + section.getLabel());
        dialog.setContentText("Title:");
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        WindowsTitleBar.styleDialog(dialog, resolvedTheme);
        Optional<String> input = dialog.showAndWait();
        if (input.isEmpty() || input.get().isBlank()) {
            return;
        }
        String rawTitle = input.get().trim();
        AppExecutors.ioExecutor().execute(() -> {
            try {
                CourseItem created = courseManager.createItem(
                        course,
                        section,
                        rawTitle,
                        "# " + rawTitle + "\n\n"
                );
                Platform.runLater(() -> {
                    reloadMarkdownListAsync(created.getFileName());
                    publishStatus("Created note " + created.getRelativeDisplayPath());
                });
            } catch (IOException ex) {
                Platform.runLater(() -> publishStatus("Could not create note: " + ex.getMessage()));
            }
        });
    }

    private void saveActiveNoteAsync() {
        autosaveTimer.stop();
        if (!dirty || activeItem == null) {
            return;
        }
        CourseItem itemToSave = activeItem;
        String textToSave = editor.getText();
        dirty = false;
        saveNowBtn.setDisable(true);
        saveStateLabel.setText("Saving...");

        AppExecutors.ioExecutor().execute(() -> {
            try {
                courseManager.saveItem(itemToSave, textToSave);
                Platform.runLater(() -> {
                    if (!dirty && itemToSave.equals(activeItem)) {
                        saveStateLabel.setText("Saved");
                    }
                    publishStatus("Saved " + itemToSave.getRelativeDisplayPath());
                });
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    dirty = true;
                    saveNowBtn.setDisable(false);
                    saveStateLabel.setText("Save failed");
                    publishStatus("Failed saving note: " + ex.getMessage());
                });
            }
        });
    }

    @Override
    public Region getView() {
        return this;
    }

    @Override
    public void refreshCourses(List<Course> courses, Course preferredCourse) {
        flushPendingEdits();
        updatingSelectors = true;
        try {
            courseBox.getItems().setAll(courses);
            Course target = WorkspaceUiHelpers.resolveTargetCourse(courses, paneState.getTargetCourse(), preferredCourse);
            courseBox.setValue(target);
            newNoteBtn.setDisable(target == null);
        } finally {
            updatingSelectors = false;
        }
        if (courseBox.getValue() != null) {
            reloadMarkdownListAsync(null);
        } else {
            clearNoteEditor();
        }
    }

    @Override
    public void flushPendingEdits() {
        autosaveTimer.stop();
        if (!dirty || activeItem == null) {
            return;
        }
        CourseItem target = activeItem;
        String content = editor.getText();
        try {
            courseManager.saveItem(target, content);
            dirty = false;
            saveNowBtn.setDisable(true);
            saveStateLabel.setText("Saved");
        } catch (IOException ex) {
            saveStateLabel.setText("Save failed");
            publishStatus("Failed saving note: " + ex.getMessage());
        }
    }

    @Override
    public void releaseResources() {
        flushPendingEdits();
        itemLoadSeq.incrementAndGet();
        clearNoteEditor();
    }

    @Override
    public void setActiveTheme(UiTheme resolvedTheme) {
        if (resolvedTheme != null) {
            this.resolvedTheme = resolvedTheme;
        }
    }

    private void publishStatus(String message) {
        if (onStatusChanged != null && message != null && !message.isBlank()) {
            onStatusChanged.accept(message);
        }
    }
}
