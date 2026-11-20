package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseExporter;
import com.calendarorganizer.course.CourseItem;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.course.CourseSection;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

public class CourseTabView extends BorderPane {

    private final CourseManager courseManager;
    private final CourseExporter courseExporter;

    private final SplitPane splitPane;
    private final VBox coursesRail;
    private final ListView<Course> courseListView = new ListView<>();
    private final List<Button> sectionButtons = new ArrayList<>();
    private final ListView<CourseItem> itemListView = new ListView<>();
    private final Label courseHeading = new Label("Select a course");
    private final Label pathHeader = new Label("No file selected");
    private final Label dirtyIndicator = new Label("");
    private final TextArea editor = new TextArea();
    private final Button saveButton = new Button("Save");
    private final Button newItemButton = new Button("+ New Markdown");
    private final Button deleteItemButton = new Button("Delete File");
    private final Button deleteCourseButton = new Button("Delete Course");

    private List<Course> allCourses = List.of();
    private String filterText = "";
    private Course selectedCourse;
    private CourseSection selectedSection = CourseSection.LECTURES;
    private CourseItem selectedItem;
    private boolean dirty;
    private boolean loadingEditor;
    private boolean railVisible = true;
    private UiTheme activeTheme = UiTheme.CHARCOAL;
    private String lastStatus = "Ready.";
    private Consumer<String> onStatusChanged;
    private Runnable onStateChanged;

    public CourseTabView(CourseManager courseManager, CourseExporter courseExporter) {
        this.courseManager = courseManager;
        this.courseExporter = courseExporter;

        this.coursesRail = buildCoursesPane();
        VBox filesPane = buildSectionFilesPane();
        VBox editorPane = buildEditorPane();

        this.splitPane = new SplitPane(coursesRail, filesPane, editorPane);
        this.splitPane.setDividerPositions(0.22, 0.45);
        setCenter(splitPane);

        refreshCourses();
    }

    private VBox buildCoursesPane() {
        Label heading = new Label("Courses");
        heading.getStyleClass().add("section-heading");

        VBox.setVgrow(courseListView, Priority.ALWAYS);
        courseListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                selectedCourse = newVal;
                courseHeading.setText(newVal.getDisplayName());
                deleteCourseButton.setDisable(false);
                newItemButton.setDisable(false);
                refreshItems();
            } else {
                selectedCourse = null;
                courseHeading.setText("Select a course");
                deleteCourseButton.setDisable(true);
                newItemButton.setDisable(true);
                itemListView.getItems().clear();
                clearEditor();
            }
            notifyStateChanged();
        });

        Button newCourseBtn = new Button("+ New Course");
        newCourseBtn.getStyleClass().add("lc-btn-primary");
        newCourseBtn.setMaxWidth(Double.MAX_VALUE);
        newCourseBtn.setOnAction(e -> handleCreateCourse());

        deleteCourseButton.setMaxWidth(Double.MAX_VALUE);
        deleteCourseButton.setDisable(true);
        deleteCourseButton.setOnAction(e -> handleDeleteCourse());

        VBox pane = new VBox(8, heading, courseListView, newCourseBtn, deleteCourseButton);
        pane.getStyleClass().add("lc-left-rail");
        pane.setPadding(new Insets(10));
        pane.setMinWidth(180);
        return pane;
    }

    private VBox buildSectionFilesPane() {
        courseHeading.getStyleClass().add("section-heading");

        HBox segmentGroup = new HBox(2);
        segmentGroup.getStyleClass().add("lc-segment-group");
        for (CourseSection section : CourseSection.values()) {
            Button btn = new Button(section.getLabel());
            btn.getStyleClass().add("lc-segment");
            btn.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(btn, Priority.ALWAYS);
            btn.setOnAction(e -> selectSection(section));
            sectionButtons.add(btn);
            segmentGroup.getChildren().add(btn);
        }
        updateSectionButtons();

        VBox.setVgrow(itemListView, Priority.ALWAYS);
        itemListView.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null) {
                loadItemIntoEditor(newItem);
            } else {
                clearEditor();
            }
        });

        newItemButton.setDisable(true);
        newItemButton.setOnAction(e -> handleCreateItem());
        deleteItemButton.setDisable(true);
        deleteItemButton.setOnAction(e -> handleDeleteItem());

        HBox buttons = new HBox(8, newItemButton, deleteItemButton);
        VBox pane = new VBox(8, courseHeading, segmentGroup, itemListView, buttons);
        pane.getStyleClass().add("lc-inset-pane");
        pane.setPadding(new Insets(10));
        pane.setMinWidth(220);
        return pane;
    }

    private VBox buildEditorPane() {
        pathHeader.getStyleClass().add("dim-label");
        dirtyIndicator.getStyleClass().add("caution-label");

        saveButton.setDisable(true);
        saveButton.setOnAction(e -> handleSaveItem());

        HBox topBar = new HBox(10, pathHeader, dirtyIndicator, saveButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pathHeader, Priority.ALWAYS);

        editor.getStyleClass().add("markdown-editor");
        editor.setWrapText(true);
        editor.setDisable(true);
        VBox.setVgrow(editor, Priority.ALWAYS);
        editor.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!loadingEditor && selectedItem != null) {
                setDirty(true);
            }
        });

        KeyCombination saveCombo = new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN);
        editor.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (saveCombo.match(ev)) {
                handleSaveItem();
                ev.consume();
            }
        });

        VBox pane = new VBox(8, topBar, editor);
        pane.getStyleClass().add("lc-canvas-pane");
        pane.setPadding(new Insets(10));
        return pane;
    }

    private void selectSection(CourseSection section) {
        this.selectedSection = section;
        updateSectionButtons();
        refreshItems();
    }

    private void updateSectionButtons() {
        CourseSection[] values = CourseSection.values();
        for (int i = 0; i < values.length; i++) {
            Button btn = sectionButtons.get(i);
            btn.getStyleClass().remove("lc-segment-active");
            if (values[i] == selectedSection) {
                btn.getStyleClass().add("lc-segment-active");
            }
        }
    }

    public void setActiveTheme(UiTheme theme) {
        this.activeTheme = theme;
    }

    public void setOnStatusChanged(Consumer<String> listener) {
        this.onStatusChanged = listener;
    }

    public void setOnStateChanged(Runnable listener) {
        this.onStateChanged = listener;
    }

    public void toggleRail() {
        railVisible = !railVisible;
        if (railVisible) {
            if (!splitPane.getItems().contains(coursesRail)) {
                splitPane.getItems().add(0, coursesRail);
                splitPane.setDividerPositions(0.22, 0.45);
            }
        } else {
            splitPane.getItems().remove(coursesRail);
            splitPane.setDividerPositions(0.32);
        }
    }

    public void setFilterText(String query) {
        if (query == null) {
            this.filterText = "";
        } else {
            this.filterText = query.trim().toLowerCase(Locale.ROOT);
        }
        applyCourseFilter();
    }

    public boolean hasSelectedCourse() {
        return selectedCourse != null;
    }

    public boolean hasCourses() {
        return !allCourses.isEmpty();
    }

    public String getActiveLocationSummary() {
        if (selectedItem == null) {
            if (selectedCourse != null) {
                return selectedCourse.getDisplayName() + " / " + selectedSection.getLabel();
            }
            return "No course file selected";
        }
        if (dirty) {
            return selectedItem.getRelativeDisplayPath() + "  (Unsaved)";
        }
        return selectedItem.getRelativeDisplayPath();
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void refreshCourses() {
        try {
            allCourses = courseManager.listCourses();
            applyCourseFilter();
        } catch (IOException ex) {
            publishStatus("Failed to load courses: " + ex.getMessage());
        }
    }

    private void applyCourseFilter() {
        List<Course> filtered = new ArrayList<>();
        for (Course course : allCourses) {
            if (filterText.isEmpty() || course.getDisplayName().toLowerCase(Locale.ROOT).contains(filterText)) {
                filtered.add(course);
            }
        }
        Course previous = selectedCourse;
        courseListView.getItems().setAll(filtered);
        if (previous != null && filtered.contains(previous)) {
            courseListView.getSelectionModel().select(previous);
        } else if (!filtered.isEmpty()) {
            courseListView.getSelectionModel().selectFirst();
        } else {
            courseListView.getSelectionModel().clearSelection();
        }
        notifyStateChanged();
    }

    private void refreshItems() {
        if (selectedCourse == null) {
            itemListView.getItems().clear();
            clearEditor();
            return;
        }
        try {
            List<CourseItem> items = courseManager.listItems(selectedCourse, selectedSection);
            CourseItem previous = selectedItem;
            itemListView.getItems().setAll(items);
            if (previous != null && items.contains(previous)) {
                itemListView.getSelectionModel().select(previous);
            } else if (!items.isEmpty()) {
                itemListView.getSelectionModel().selectFirst();
            } else {
                clearEditor();
            }
        } catch (IOException ex) {
            publishStatus("Failed to list markdown files: " + ex.getMessage());
        }
    }

    private void handleCreateCourse() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("New Course");
        dialog.setHeaderText("Create a new course folder");
        dialog.setContentText("Course code/name (e.g. CMPT 201):");
        applyDialogTheme(dialog.getDialogPane());

        Optional<String> entered = dialog.showAndWait();
        if (entered.isEmpty()) {
            return;
        }
        try {
            Course created = courseManager.createCourse(entered.get());
            refreshCourses();
            courseListView.getSelectionModel().select(created);
            publishStatus("Created course: " + created.getDisplayName());
        } catch (IllegalArgumentException | IOException ex) {
            publishStatus("Could not create course: " + ex.getMessage());
        }
    }

    private void handleDeleteCourse() {
        if (selectedCourse == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(getScene().getWindow());
        confirm.setTitle("Delete Course");
        confirm.setHeaderText("Delete " + selectedCourse.getDisplayName() + " and all its files?");
        applyDialogTheme(confirm.getDialogPane());

        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        try {
            String name = selectedCourse.getDisplayName();
            courseManager.deleteCourse(selectedCourse);
            selectedCourse = null;
            selectedItem = null;
            refreshCourses();
            publishStatus("Deleted course: " + name);
        } catch (IOException ex) {
            publishStatus("Delete failed: " + ex.getMessage());
        }
    }

    private void handleCreateItem() {
        if (selectedCourse == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("New Markdown File");
        dialog.setHeaderText("New file in " + selectedCourse.getDisplayName() + " / " + selectedSection.getLabel());
        dialog.setContentText("File title (leave blank to auto-number):");
        applyDialogTheme(dialog.getDialogPane());

        Optional<String> entered = dialog.showAndWait();
        if (entered.isEmpty()) {
            return;
        }
        try {
            CourseItem created = courseManager.createItem(selectedCourse, selectedSection, entered.get(), null);
            refreshItems();
            itemListView.getSelectionModel().select(created);
            publishStatus("Created " + created.getRelativeDisplayPath());
        } catch (IllegalArgumentException | IOException ex) {
            publishStatus("Could not create file: " + ex.getMessage());
        }
    }

    private void handleDeleteItem() {
        if (selectedItem == null) {
            return;
        }
        try {
            String path = selectedItem.getRelativeDisplayPath();
            courseManager.deleteItem(selectedItem);
            selectedItem = null;
            refreshItems();
            publishStatus("Deleted " + path);
        } catch (IOException ex) {
            publishStatus("Delete failed: " + ex.getMessage());
        }
    }

    private void handleSaveItem() {
        if (selectedItem == null) {
            return;
        }
        try {
            courseManager.saveItem(selectedItem, editor.getText());
            setDirty(false);
            publishStatus("Saved " + selectedItem.getRelativeDisplayPath());
        } catch (IOException ex) {
            publishStatus("Save failed: " + ex.getMessage());
        }
    }

    private void loadItemIntoEditor(CourseItem item) {
        if (dirty && selectedItem != null && !selectedItem.equals(item)) {
            handleSaveItem();
        }
        loadingEditor = true;
        try {
            String text = courseManager.readItem(item);
            selectedItem = item;
            pathHeader.setText(item.getRelativeDisplayPath());
            editor.setText(text);
            editor.setDisable(false);
            deleteItemButton.setDisable(false);
            saveButton.setDisable(false);
            setDirty(false);
        } catch (IOException ex) {
            publishStatus("Could not read file: " + ex.getMessage());
        } finally {
            loadingEditor = false;
        }
    }

    private void clearEditor() {
        loadingEditor = true;
        selectedItem = null;
        pathHeader.setText("No file selected");
        editor.clear();
        editor.setDisable(true);
        deleteItemButton.setDisable(true);
        saveButton.setDisable(true);
        setDirty(false);
        loadingEditor = false;
    }

    private void setDirty(boolean isDirty) {
        this.dirty = isDirty;
        if (isDirty) {
            dirtyIndicator.setText("Unsaved");
        } else {
            dirtyIndicator.setText("");
        }
        notifyStateChanged();
    }

    public void exportSelectedCourse() {
        if (selectedCourse == null) {
            return;
        }
        if (dirty && selectedItem != null) {
            handleSaveItem();
        }
        Course targetCourse = selectedCourse;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Course Study Zip");
        chooser.setInitialFileName(targetCourse.getFolderName() + "-study.zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP archive (*.zip)", "*.zip"));
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        publishStatus("Exporting " + targetCourse.getDisplayName() + "...");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                courseExporter.exportCourse(targetCourse, file.toPath());
                return null;
            }
        };
        task.setOnSucceeded(ev -> publishStatus("Exported " + targetCourse.getDisplayName() + " to " + file.getAbsolutePath()));
        task.setOnFailed(ev -> {
            Throwable err = task.getException();
            if (err != null) {
                publishStatus("Export failed: " + err.getMessage());
            } else {
                publishStatus("Export failed.");
            }
        });
        Thread worker = new Thread(task, "course-export");
        worker.setDaemon(true);
        worker.start();
    }

    public void exportAllCourses() {
        if (dirty && selectedItem != null) {
            handleSaveItem();
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export All Courses Study Zip");
        chooser.setInitialFileName("all-courses-study.zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP archive (*.zip)", "*.zip"));
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        publishStatus("Exporting all courses...");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                courseExporter.exportAllCourses(file.toPath());
                return null;
            }
        };
        task.setOnSucceeded(ev -> publishStatus("Exported all courses to " + file.getAbsolutePath()));
        task.setOnFailed(ev -> {
            Throwable err = task.getException();
            if (err != null) {
                publishStatus("Export failed: " + err.getMessage());
            } else {
                publishStatus("Export failed.");
            }
        });
        Thread worker = new Thread(task, "course-export-all");
        worker.setDaemon(true);
        worker.start();
    }

    private void publishStatus(String message) {
        this.lastStatus = message;
        if (onStatusChanged != null) {
            onStatusChanged.accept(message);
        }
    }

    private void notifyStateChanged() {
        if (onStateChanged != null) {
            onStateChanged.run();
        }
    }

    private void applyDialogTheme(DialogPane pane) {
        URL cssUrl = CourseTabView.class.getResource("/styles.css");
        if (cssUrl != null) {
            String urlStr = cssUrl.toExternalForm();
            if (!pane.getStylesheets().contains(urlStr)) {
                pane.getStylesheets().add(urlStr);
            }
        }
        for (UiTheme candidate : UiTheme.values()) {
            pane.getStyleClass().remove(candidate.getStyleClass());
        }
        pane.getStyleClass().add(activeTheme.getStyleClass());
    }
}
