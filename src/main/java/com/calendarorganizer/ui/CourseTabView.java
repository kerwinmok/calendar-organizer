package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseExporter;
import com.calendarorganizer.course.CourseItem;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.course.CourseSection;
import com.calendarorganizer.service.CalendarService;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final Button openFileButton = new Button("Open File");
    private final Button openFolderButton = new Button("Open Folder");
    private final Button newItemButton = new Button("+ New Note");
    private final Button importFileButton = new Button("Import File");
    private final Button deleteItemButton = new Button("Delete");
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
                importFileButton.setDisable(false);
                openFolderButton.setDisable(false);
                refreshItems();
            } else {
                selectedCourse = null;
                courseHeading.setText("Select a course");
                deleteCourseButton.setDisable(true);
                newItemButton.setDisable(true);
                importFileButton.setDisable(true);
                openFolderButton.setDisable(true);
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
        itemListView.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2 && selectedItem != null && !selectedItem.isMarkdown()) {
                handleOpenExternalFile();
            }
        });

        Label dropHint = new Label("Drag & drop PDF, PPTX, or MD files here");
        dropHint.getStyleClass().add("dim-label");

        newItemButton.setDisable(true);
        newItemButton.setOnAction(e -> handleCreateItem());
        importFileButton.setDisable(true);
        importFileButton.setOnAction(e -> handleImportFilesDialog());
        deleteItemButton.setDisable(true);
        deleteItemButton.setOnAction(e -> handleDeleteItem());

        HBox buttons = new HBox(6, newItemButton, importFileButton, deleteItemButton);
        VBox pane = new VBox(8, courseHeading, segmentGroup, itemListView, dropHint, buttons);
        pane.getStyleClass().add("lc-inset-pane");
        pane.setPadding(new Insets(10));
        pane.setMinWidth(235);
        installFileDragAndDrop(pane);
        return pane;
    }

    private void installFileDragAndDrop(VBox targetPane) {
        targetPane.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (selectedCourse != null && db.hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        targetPane.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (selectedCourse != null && db.hasFiles()) {
                importFilesList(db.getFiles());
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private VBox buildEditorPane() {
        pathHeader.getStyleClass().add("dim-label");
        dirtyIndicator.getStyleClass().add("caution-label");

        openFileButton.setDisable(true);
        openFileButton.setOnAction(e -> handleOpenExternalFile());
        openFolderButton.setDisable(true);
        openFolderButton.setOnAction(e -> handleOpenSectionFolder());
        saveButton.setDisable(true);
        saveButton.setOnAction(e -> handleSaveItem());

        HBox topBar = new HBox(8, pathHeader, dirtyIndicator, openFileButton, openFolderButton, saveButton);
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
        activeTheme.applyToDialog(dialog.getDialogPane());

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
        activeTheme.applyToDialog(confirm.getDialogPane());

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
        activeTheme.applyToDialog(dialog.getDialogPane());

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
        if (selectedItem == null || !selectedItem.isMarkdown()) {
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

    private void handleImportFilesDialog() {
        if (selectedCourse == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Slides, PDF, or Notes into " + selectedCourse.getDisplayName());
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Class Files (*.pdf, *.pptx, *.ppt, *.docx, *.md, *.txt, *.png, *.jpg)",
                        "*.pdf", "*.pptx", "*.ppt", "*.docx", "*.md", "*.txt", "*.png", "*.jpg"),
                new FileChooser.ExtensionFilter("All Files (*.*)", "*.*")
        );
        List<File> chosen = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (chosen != null && !chosen.isEmpty()) {
            importFilesList(chosen);
        }
    }

    private void importFilesList(List<File> files) {
        if (selectedCourse == null || files == null || files.isEmpty()) {
            return;
        }
        CourseItem lastImported = null;
        int importedCount = 0;
        for (File file : files) {
            try {
                lastImported = courseManager.importExternalFile(selectedCourse, selectedSection, file.toPath());
                importedCount++;
            } catch (IllegalArgumentException | IOException ex) {
                publishStatus("Could not import " + file.getName() + ": " + ex.getMessage());
            }
        }
        if (importedCount > 0) {
            refreshItems();
            if (lastImported != null) {
                itemListView.getSelectionModel().select(lastImported);
            }
            publishStatus("Stored " + importedCount + " file(s) in " + selectedCourse.getDisplayName() + " / " + selectedSection.getLabel());
        }
    }

    private void handleOpenExternalFile() {
        if (selectedItem == null) {
            return;
        }
        openPathInDesktop(selectedItem.getFilePath());
    }

    private void handleOpenSectionFolder() {
        if (selectedCourse == null) {
            return;
        }
        Path folder = selectedCourse.getDirectory().resolve(selectedSection.getFolderName());
        openPathInDesktop(folder);
    }

    private void openPathInDesktop(Path target) {
        if (!Desktop.isDesktopSupported()) {
            publishStatus("Desktop open is not supported on this system.");
            return;
        }
        CalendarService.backgroundExecutor().execute(() -> {
            try {
                Desktop.getDesktop().open(target.toFile());
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() ->
                        publishStatus("Could not open " + target.getFileName() + ": " + ex.getMessage()));
            }
        });
    }

    private void loadItemIntoEditor(CourseItem item) {
        if (dirty && selectedItem != null && selectedItem.isMarkdown() && !selectedItem.equals(item)) {
            handleSaveItem();
        }
        loadingEditor = true;
        try {
            selectedItem = item;
            pathHeader.setText(item.getRelativeDisplayPath());
            deleteItemButton.setDisable(false);
            openFileButton.setDisable(false);

            if (item.isMarkdown()) {
                String text = courseManager.readItem(item);
                editor.setText(text);
                editor.setDisable(false);
                saveButton.setDisable(false);
            } else {
                long bytes = Files.size(item.getFilePath());
                long kb = Math.max(1L, Math.round(bytes / 1024.0));
                editor.setText("Stored locally at:\n"
                        + item.getFilePath().toAbsolutePath() + "\n"
                        + "Size: " + kb + " KB\n\n"
                        + "Click 'Open File' above (or double-click the file in the list) to open this PowerPoint/PDF in your default application,\n"
                        + "or click 'Open Folder' to view it in Windows Explorer.");
                editor.setDisable(true);
                saveButton.setDisable(true);
            }
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
        openFileButton.setDisable(true);
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
        CalendarService.backgroundExecutor().execute(task);
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
        CalendarService.backgroundExecutor().execute(task);
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
}
