package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseExporter;
import com.calendarorganizer.course.CourseItem;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.course.CourseSection;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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
import java.util.List;
import java.util.Optional;

public class CourseTabView extends BorderPane {

    private final CourseManager courseManager;
    private final CourseExporter courseExporter;

    private final ListView<Course> courseListView = new ListView<>();
    private final TabPane sectionTabs = new TabPane();
    private final ListView<CourseItem> itemListView = new ListView<>();
    private final Label courseHeading = new Label("Select a course");
    private final Label pathHeader = new Label("No file selected");
    private final Label dirtyIndicator = new Label("");
    private final TextArea editor = new TextArea();
    private final Button saveButton = new Button("Save");
    private final Button newItemButton = new Button("+ New Markdown");
    private final Button deleteItemButton = new Button("Delete File");
    private final Button deleteCourseButton = new Button("Delete Course");
    private final Button exportCourseButton = new Button("Export Course (.zip)");
    private final Button exportAllButton = new Button("Export All (.zip)");
    private final Label statusLabel = new Label("Ready.");

    private Course selectedCourse;
    private CourseSection selectedSection = CourseSection.LECTURES;
    private CourseItem selectedItem;
    private boolean dirty;
    private boolean loadingEditor;

    public CourseTabView(CourseManager courseManager, CourseExporter courseExporter) {
        this.courseManager = courseManager;
        this.courseExporter = courseExporter;

        SplitPane splitPane = new SplitPane(
                buildCoursesPane(),
                buildSectionFilesPane(),
                buildEditorPane()
        );
        splitPane.setDividerPositions(0.22, 0.46);
        setCenter(splitPane);
        setBottom(buildStatusBar());

        refreshCourses();
    }

    private VBox buildCoursesPane() {
        Label heading = new Label("Courses");
        heading.getStyleClass().add("section-heading");

        VBox.setVgrow(courseListView, Priority.ALWAYS);
        courseListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                selectedCourse = newVal;
                courseHeading.setText(newVal.getDisplayName() + " (" + newVal.getFolderName() + ")");
                deleteCourseButton.setDisable(false);
                exportCourseButton.setDisable(false);
                newItemButton.setDisable(false);
                refreshItems();
            } else {
                selectedCourse = null;
                courseHeading.setText("Select a course");
                deleteCourseButton.setDisable(true);
                exportCourseButton.setDisable(true);
                newItemButton.setDisable(true);
                itemListView.getItems().clear();
                clearEditor();
            }
        });

        Button newCourseBtn = new Button("+ New Course");
        newCourseBtn.setMaxWidth(Double.MAX_VALUE);
        newCourseBtn.setOnAction(e -> handleCreateCourse());

        deleteCourseButton.setMaxWidth(Double.MAX_VALUE);
        deleteCourseButton.setDisable(true);
        deleteCourseButton.setOnAction(e -> handleDeleteCourse());

        exportCourseButton.setMaxWidth(Double.MAX_VALUE);
        exportCourseButton.setDisable(true);
        exportCourseButton.setOnAction(e -> handleExportSingleCourse());
        exportAllButton.setMaxWidth(Double.MAX_VALUE);
        exportAllButton.setOnAction(e -> handleExportAllCourses());

        VBox pane = new VBox(8, heading, courseListView, newCourseBtn, deleteCourseButton,
                new Separator(), exportCourseButton, exportAllButton);
        pane.setPadding(new Insets(10));
        return pane;
    }

    private VBox buildSectionFilesPane() {
        courseHeading.getStyleClass().add("section-heading");

        sectionTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (CourseSection section : CourseSection.values()) {
            Tab tab = new Tab(section.getLabel());
            tab.setUserData(section);
            sectionTabs.getTabs().add(tab);
        }
        sectionTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != null && newTab.getUserData() instanceof CourseSection) {
                selectedSection = (CourseSection) newTab.getUserData();
                refreshItems();
            }
        });

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
        VBox pane = new VBox(8, courseHeading, sectionTabs, itemListView, buttons);
        pane.setPadding(new Insets(10));
        return pane;
    }

    private VBox buildEditorPane() {
        pathHeader.getStyleClass().add("section-heading");
        dirtyIndicator.setStyle("-fx-text-fill: #b45309; -fx-font-weight: bold;");

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
        pane.setPadding(new Insets(10));
        return pane;
    }

    private HBox buildStatusBar() {
        statusLabel.getStyleClass().add("status-label");
        HBox bar = new HBox(statusLabel);
        bar.setPadding(new Insets(6, 12, 6, 12));
        bar.getStyleClass().add("toolbar-bar");
        return bar;
    }

    public Button getExportCourseButton() {
        return exportCourseButton;
    }

    public Button getExportAllButton() {
        return exportAllButton;
    }

    public Course getSelectedCourse() {
        return selectedCourse;
    }

    public void setStatus(String message) {
        statusLabel.setText(message);
    }

    public void refreshCourses() {
        try {
            List<Course> courses = courseManager.listCourses();
            Course previous = selectedCourse;
            courseListView.getItems().setAll(courses);
            if (previous != null && courses.contains(previous)) {
                courseListView.getSelectionModel().select(previous);
            } else if (!courses.isEmpty()) {
                courseListView.getSelectionModel().selectFirst();
            } else {
                courseListView.getSelectionModel().clearSelection();
            }
            exportAllButton.setDisable(courses.isEmpty());
        } catch (IOException ex) {
            statusLabel.setText("Failed to load courses: " + ex.getMessage());
        }
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
            statusLabel.setText("Failed to list markdown files: " + ex.getMessage());
        }
    }

    private void handleCreateCourse() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Course");
        dialog.setHeaderText("Create a new course folder");
        dialog.setContentText("Course code/name (e.g. CMPT 201):");
        Optional<String> entered = dialog.showAndWait();
        if (entered.isEmpty()) {
            return;
        }
        try {
            Course created = courseManager.createCourse(entered.get());
            refreshCourses();
            courseListView.getSelectionModel().select(created);
            statusLabel.setText("Created course: " + created.getDisplayName());
        } catch (IllegalArgumentException | IOException ex) {
            statusLabel.setText("Could not create course: " + ex.getMessage());
        }
    }

    private void handleDeleteCourse() {
        if (selectedCourse == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Course");
        confirm.setHeaderText("Delete " + selectedCourse.getDisplayName() + " and all its files?");
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
            statusLabel.setText("Deleted course: " + name);
        } catch (IOException ex) {
            statusLabel.setText("Delete failed: " + ex.getMessage());
        }
    }

    private void handleCreateItem() {
        if (selectedCourse == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Markdown File");
        dialog.setHeaderText("New file in " + selectedCourse.getDisplayName() + " / " + selectedSection.getLabel());
        dialog.setContentText("File title (leave blank to auto-number):");
        Optional<String> entered = dialog.showAndWait();
        if (entered.isEmpty()) {
            return;
        }
        try {
            CourseItem created = courseManager.createItem(selectedCourse, selectedSection, entered.get(), null);
            refreshItems();
            itemListView.getSelectionModel().select(created);
            statusLabel.setText("Created " + created.getRelativeDisplayPath());
        } catch (IllegalArgumentException | IOException ex) {
            statusLabel.setText("Could not create file: " + ex.getMessage());
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
            statusLabel.setText("Deleted " + path);
        } catch (IOException ex) {
            statusLabel.setText("Delete failed: " + ex.getMessage());
        }
    }

    private void handleSaveItem() {
        if (selectedItem == null) {
            return;
        }
        try {
            courseManager.saveItem(selectedItem, editor.getText());
            setDirty(false);
            statusLabel.setText("Saved " + selectedItem.getRelativeDisplayPath());
        } catch (IOException ex) {
            statusLabel.setText("Save failed: " + ex.getMessage());
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
            statusLabel.setText("Could not read file: " + ex.getMessage());
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
            dirtyIndicator.setText("* Unsaved");
        } else {
            dirtyIndicator.setText("");
        }
    }

    private void handleExportSingleCourse() {
        if (selectedCourse == null) {
            return;
        }
        if (dirty && selectedItem != null) {
            handleSaveItem();
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Course Study Zip");
        chooser.setInitialFileName(selectedCourse.getFolderName() + "-study.zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP archive (*.zip)", "*.zip"));
        File file = chooser.showSaveDialog(getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            courseExporter.exportCourse(selectedCourse, file.toPath());
            statusLabel.setText("Exported " + selectedCourse.getDisplayName() + " to " + file.getAbsolutePath());
        } catch (IOException ex) {
            statusLabel.setText("Export failed: " + ex.getMessage());
        }
    }

    private void handleExportAllCourses() {
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
        try {
            courseExporter.exportAllCourses(file.toPath());
            statusLabel.setText("Exported all courses to " + file.getAbsolutePath());
        } catch (IOException ex) {
            statusLabel.setText("Export failed: " + ex.getMessage());
        }
    }
}
