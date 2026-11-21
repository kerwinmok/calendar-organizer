package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseExporter;
import com.calendarorganizer.course.CourseFilePreviewer;
import com.calendarorganizer.course.CourseItem;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.course.CourseSection;
import com.calendarorganizer.course.CourseSortOrder;
import com.calendarorganizer.course.PdfRenderService;
import com.calendarorganizer.service.AppExecutors;
import com.calendarorganizer.service.CalendarService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class CourseTabView extends BorderPane {

    private final CourseManager courseManager;
    private final CourseExporter courseExporter;
    private final CourseFilePreviewer previewer = new CourseFilePreviewer();
    private final AtomicLong coursesRequestSeq = new AtomicLong(0L);
    private final AtomicLong itemsRequestSeq = new AtomicLong(0L);
    private final AtomicLong previewRequestSeq = new AtomicLong(0L);

    private final SplitPane splitPane;
    private final VBox coursesRail;
    private final ListView<Course> courseListView = new ListView<>();
    private final List<Button> sectionButtons = new ArrayList<>();
    private final ComboBox<CourseSortOrder> sortBox = new ComboBox<>();
    private final ListView<CourseItem> itemListView = new ListView<>();
    private final Label courseHeading = new Label("Select a course");
    private final Label pathHeader = new Label("No file selected");
    private final Label dirtyIndicator = new Label("");
    private final TextArea editor = new TextArea();
    private final HBox previewNavBar = new HBox(8);
    private final Button prevPageButton = new Button("< Prev");
    private final Button nextPageButton = new Button("Next >");
    private final Label pageIndicatorLabel = new Label("");
    private final Button zoomOutButton = new Button("-");
    private final Label zoomReadoutLabel = new Label("Fit");
    private final Button zoomInButton = new Button("+");
    private final Button zoomFitButton = new Button("Fit");
    private final TextArea previewTextArea = new TextArea();
    private final ImageView pptxThumbView = new ImageView();
    private final VBox previewBox = new VBox(8);
    private final ImageView previewImageView = new ImageView();
    private final ScrollPane imageScrollPane = new ScrollPane();
    private final ListView<PdfPageSlot> pdfPagesListView = new ListView<>();
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
    private CourseSortOrder activeSortOrder = CourseSortOrder.NAME_ASC;
    private CourseItem selectedItem;
    private CourseFilePreviewer.PreviewKind activePreviewKind = CourseFilePreviewer.PreviewKind.UNSUPPORTED;
    private List<String> activePptxSlides = List.of();
    private int activePageIndex;
    private int activeTotalPages;
    private boolean pdfZoomFit = true;
    private double pdfZoomFactor = 1.0;
    private boolean dirty;
    private boolean loadingEditor;
    private boolean railVisible = true;
    private UiTheme activeTheme = UiTheme.CHARCOAL;
    private String lastStatus = "Ready.";
    private Consumer<String> onStatusChanged;
    private Runnable onStateChanged;
    private Consumer<CourseSortOrder> onSortOrderChanged;

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
            flushPendingEdits();
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

        sortBox.getItems().setAll(CourseSortOrder.values());
        sortBox.setValue(activeSortOrder);
        sortBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sortBox, Priority.ALWAYS);
        sortBox.setOnAction(e -> {
            CourseSortOrder chosen = sortBox.getValue();
            if (chosen != null && chosen != activeSortOrder) {
                activeSortOrder = chosen;
                if (onSortOrderChanged != null) {
                    onSortOrderChanged.accept(chosen);
                }
                refreshItems();
            }
        });
        Label sortLabel = new Label("Sort");
        sortLabel.getStyleClass().add("dim-label");
        HBox sortRow = new HBox(8, sortLabel, sortBox);
        sortRow.setAlignment(Pos.CENTER_LEFT);

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
        VBox pane = new VBox(8, courseHeading, segmentGroup, sortRow, itemListView, dropHint, buttons);
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

        pageIndicatorLabel.getStyleClass().add("dim-label");
        prevPageButton.setOnAction(e -> stepPreviewPage(-1));
        nextPageButton.setOnAction(e -> stepPreviewPage(1));
        zoomOutButton.setOnAction(e -> adjustPdfZoom(-0.15));
        zoomInButton.setOnAction(e -> adjustPdfZoom(0.15));
        zoomFitButton.setOnAction(e -> resetPdfZoomToFit());
        zoomReadoutLabel.getStyleClass().add("dim-label");
        Region navSpacer = new Region();
        HBox.setHgrow(navSpacer, Priority.ALWAYS);
        previewNavBar.setAlignment(Pos.CENTER_LEFT);
        previewNavBar.getChildren().setAll(
                prevPageButton, nextPageButton, pageIndicatorLabel,
                navSpacer, zoomOutButton, zoomReadoutLabel, zoomInButton, zoomFitButton
        );
        hidePreviewNavBar();

        editor.getStyleClass().add("markdown-editor");
        editor.setWrapText(true);
        editor.setDisable(true);
        editor.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!loadingEditor && selectedItem != null && selectedItem.isMarkdown()) {
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

        previewTextArea.getStyleClass().add("markdown-editor");
        previewTextArea.setEditable(false);
        previewTextArea.setWrapText(true);
        VBox.setVgrow(previewTextArea, Priority.ALWAYS);

        pptxThumbView.setPreserveRatio(true);
        pptxThumbView.setFitHeight(150);
        pptxThumbView.setVisible(false);
        pptxThumbView.setManaged(false);

        previewBox.getChildren().setAll(pptxThumbView, previewTextArea);
        previewBox.setVisible(false);
        previewBox.setManaged(false);

        previewImageView.setPreserveRatio(true);
        previewImageView.setSmooth(true);
        StackPane imageHolder = new StackPane(previewImageView);
        imageHolder.setPadding(new Insets(6));
        imageScrollPane.setContent(imageHolder);
        imageScrollPane.setFitToWidth(true);
        imageScrollPane.widthProperty().addListener((obs, oldW, newW) -> updateImageFitWidth(newW.doubleValue()));
        imageScrollPane.setVisible(false);
        imageScrollPane.setManaged(false);

        configurePdfPagesListView();

        StackPane contentHost = new StackPane(editor, previewBox, imageScrollPane, pdfPagesListView);
        VBox.setVgrow(contentHost, Priority.ALWAYS);

        VBox pane = new VBox(8, topBar, previewNavBar, contentHost);
        pane.getStyleClass().add("lc-canvas-pane");
        pane.setPadding(new Insets(10));
        return pane;
    }

    private void configurePdfPagesListView() {
        pdfPagesListView.getStyleClass().add("pdf-pages-list");
        pdfPagesListView.setCellFactory(list -> new ListCell<>() {
            private final Label pageLabel = new Label();
            private final Label loadingHint = new Label();
            private final ImageView pageImage = new ImageView();
            private final StackPane imageFrame = new StackPane(loadingHint, pageImage);
            private final VBox cellBox = new VBox(4, pageLabel, imageFrame);
            private long cellTicket;

            {
                pageLabel.getStyleClass().add("dim-label");
                loadingHint.getStyleClass().add("dim-label");
                pageImage.setPreserveRatio(true);
                pageImage.setSmooth(true);
                imageFrame.getStyleClass().add("pdf-page-frame");
                imageFrame.setAlignment(Pos.CENTER);
                cellBox.setAlignment(Pos.TOP_CENTER);
            }

            @Override
            protected void updateItem(PdfPageSlot slot, boolean empty) {
                super.updateItem(slot, empty);
                long ticket = ++cellTicket;
                if (empty || slot == null) {
                    pageImage.setImage(null);
                    setGraphic(null);
                    return;
                }

                double targetWidth = computePdfSlotTargetWidth();
                double targetHeight = Math.max(120.0, targetWidth * slot.aspectRatio);
                imageFrame.setMinSize(targetWidth, targetHeight);
                imageFrame.setPrefSize(targetWidth, targetHeight);
                imageFrame.setMaxSize(targetWidth, targetHeight);
                pageImage.setFitWidth(targetWidth);
                pageImage.setFitHeight(targetHeight);
                pageLabel.setText("Page " + (slot.pageIndex + 1) + " / " + slot.totalPages);

                int renderWidth = (int) Math.round(targetWidth);
                WritableImage cached = previewer.getCachedPdfPageFxImage(slot.pdfPath, slot.pageIndex, renderWidth);
                if (cached != null) {
                    loadingHint.setVisible(false);
                    pageImage.setImage(cached);
                    setGraphic(cellBox);
                    return;
                }

                pageImage.setImage(null);
                loadingHint.setText("Loading page " + (slot.pageIndex + 1) + "...");
                loadingHint.setVisible(true);
                setGraphic(cellBox);

                AppExecutors.renderExecutor().execute(() -> {
                    if (previewRequestSeq.get() != slot.requestId || cellTicket != ticket) {
                        return;
                    }
                    try {
                        WritableImage rendered = previewer.renderPdfPageFxImage(slot.pdfPath, slot.pageIndex, renderWidth);
                        Platform.runLater(() -> {
                            if (cellTicket == ticket
                                    && previewRequestSeq.get() == slot.requestId
                                    && !isEmpty()
                                    && getItem() == slot) {
                                loadingHint.setVisible(false);
                                pageImage.setImage(rendered);
                            }
                        });
                    } catch (IOException ex) {
                        Platform.runLater(() -> {
                            if (cellTicket == ticket
                                    && previewRequestSeq.get() == slot.requestId
                                    && !isEmpty()
                                    && getItem() == slot) {
                                loadingHint.setText("Failed to render page " + (slot.pageIndex + 1));
                                loadingHint.setVisible(true);
                            }
                        });
                    }
                });
            }
        });
        pdfPagesListView.addEventFilter(ScrollEvent.SCROLL, ev -> {
            if (ev.isControlDown() && ev.getDeltaY() != 0.0 && activePreviewKind == CourseFilePreviewer.PreviewKind.PDF) {
                adjustPdfZoom(ev.getDeltaY() > 0 ? 0.15 : -0.15);
                ev.consume();
            }
        });
        pdfPagesListView.widthProperty().addListener((obs, oldW, newW) -> {
            if (pdfZoomFit && pdfPagesListView.isVisible() && !pdfPagesListView.getItems().isEmpty()) {
                pdfPagesListView.refresh();
            }
        });
        pdfPagesListView.setVisible(false);
        pdfPagesListView.setManaged(false);
    }

    private void selectSection(CourseSection section) {
        if (section == this.selectedSection) {
            return;
        }
        flushPendingEdits();
        this.selectedSection = section;
        updateSectionButtons();
        refreshItems();
    }

    public void flushPendingEdits() {
        if (dirty && selectedItem != null && selectedItem.isMarkdown()) {
            try {
                courseManager.saveItem(selectedItem, editor.getText());
                setDirty(false);
                publishStatus("Saved " + selectedItem.getRelativeDisplayPath());
            } catch (IOException ex) {
                publishStatus("Save failed: " + ex.getMessage());
            }
        }
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

    public void setOnSortOrderChanged(Consumer<CourseSortOrder> listener) {
        this.onSortOrderChanged = listener;
    }

    public void setSortOrder(CourseSortOrder order) {
        if (order == null || order == activeSortOrder) {
            return;
        }
        this.activeSortOrder = order;
        sortBox.setValue(order);
        refreshItems();
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

    public CourseFilePreviewer getPreviewer() {
        return previewer;
    }

    public Course getSelectedCourse() {
        return selectedCourse;
    }

    public CourseSection getSelectedSection() {
        return selectedSection;
    }

    public CourseItem getSelectedItem() {
        return selectedItem;
    }

    public CourseSortOrder getActiveSortOrder() {
        return activeSortOrder;
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
        refreshCourses(null);
    }

    private void refreshCourses(Course preferredSelection) {
        long seq = coursesRequestSeq.incrementAndGet();
        AppExecutors.ioExecutor().execute(() -> {
            try {
                List<Course> loaded = courseManager.listCourses();
                Platform.runLater(() -> {
                    if (coursesRequestSeq.get() != seq) {
                        return;
                    }
                    allCourses = loaded;
                    applyCourseFilter(preferredSelection);
                });
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    if (coursesRequestSeq.get() == seq) {
                        publishStatus("Failed to load courses: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void applyCourseFilter() {
        applyCourseFilter(null);
    }

    private void applyCourseFilter(Course preferredSelection) {
        flushPendingEdits();
        List<Course> filtered = new ArrayList<>();
        for (Course course : allCourses) {
            if (filterText.isEmpty() || course.getDisplayName().toLowerCase(Locale.ROOT).contains(filterText)) {
                filtered.add(course);
            }
        }
        Course target = preferredSelection != null ? preferredSelection : selectedCourse;
        courseListView.getItems().setAll(filtered);
        if (target != null && filtered.contains(target)) {
            courseListView.getSelectionModel().select(target);
        } else if (!filtered.isEmpty()) {
            courseListView.getSelectionModel().selectFirst();
        } else {
            courseListView.getSelectionModel().clearSelection();
        }
        notifyStateChanged();
    }

    private void refreshItems() {
        refreshItems(null);
    }

    private void refreshItems(CourseItem preferredSelection) {
        if (selectedCourse == null) {
            itemsRequestSeq.incrementAndGet();
            itemListView.getItems().clear();
            clearEditor();
            return;
        }
        Course targetCourse = selectedCourse;
        CourseSection targetSection = selectedSection;
        CourseSortOrder targetOrder = activeSortOrder;
        long seq = itemsRequestSeq.incrementAndGet();
        AppExecutors.ioExecutor().execute(() -> {
            try {
                List<CourseItem> items = courseManager.listItems(targetCourse, targetSection, targetOrder);
                Platform.runLater(() -> {
                    if (itemsRequestSeq.get() != seq
                            || !targetCourse.equals(selectedCourse)
                            || targetSection != selectedSection) {
                        return;
                    }
                    CourseItem target = preferredSelection != null ? preferredSelection : selectedItem;
                    itemListView.getItems().setAll(items);
                    if (target != null && items.contains(target)) {
                        itemListView.getSelectionModel().select(target);
                    } else if (!items.isEmpty()) {
                        itemListView.getSelectionModel().selectFirst();
                    } else {
                        clearEditor();
                    }
                });
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    if (itemsRequestSeq.get() == seq) {
                        publishStatus("Failed to list course files: " + ex.getMessage());
                    }
                });
            }
        });
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
            refreshCourses(created);
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
            setDirty(false);
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
            refreshItems(created);
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
            setDirty(false);
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
        CourseItem target = selectedItem;
        String content = editor.getText();
        setDirty(false);
        AppExecutors.ioExecutor().execute(() -> {
            try {
                courseManager.saveItem(target, content);
                Platform.runLater(() -> publishStatus("Saved " + target.getRelativeDisplayPath()));
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    if (target.equals(selectedItem)) {
                        setDirty(true);
                    }
                    publishStatus("Save failed: " + ex.getMessage());
                });
            }
        });
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
        Course targetCourse = selectedCourse;
        CourseSection targetSection = selectedSection;
        List<Path> sourcePaths = new ArrayList<>();
        for (File file : files) {
            sourcePaths.add(file.toPath());
        }
        publishStatus("Importing " + sourcePaths.size() + " file(s)...");

        Task<ImportBatchResult> task = new Task<>() {
            @Override
            protected ImportBatchResult call() {
                CourseItem lastImported = null;
                int importedCount = 0;
                List<String> errors = new ArrayList<>();
                for (Path source : sourcePaths) {
                    try {
                        lastImported = courseManager.importExternalFile(targetCourse, targetSection, source);
                        importedCount++;
                    } catch (IllegalArgumentException | IOException ex) {
                        errors.add(source.getFileName() + " (" + ex.getMessage() + ")");
                    }
                }
                return new ImportBatchResult(importedCount, lastImported, errors);
            }
        };

        task.setOnSucceeded(ev -> {
            ImportBatchResult result = task.getValue();
            if (result.importedCount > 0 && targetCourse.equals(selectedCourse) && targetSection == selectedSection) {
                refreshItems(result.lastImported);
            }
            if (result.errors.isEmpty()) {
                publishStatus("Stored " + result.importedCount + " file(s) in "
                        + targetCourse.getDisplayName() + " / " + targetSection.getLabel());
            } else if (result.importedCount > 0) {
                publishStatus("Stored " + result.importedCount + " file(s); skipped "
                        + result.errors.size() + ": " + String.join(", ", result.errors));
            } else {
                publishStatus("Could not import: " + String.join(", ", result.errors));
            }
        });
        task.setOnFailed(ev -> publishStatus("Import failed."));
        CalendarService.backgroundExecutor().execute(task);
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
        if (target == null || !Files.exists(target)) {
            publishStatus("Path does not exist.");
            return;
        }
        CalendarService.backgroundExecutor().execute(() -> {
            try {
                String absPath = target.toAbsolutePath().toString();
                if (Files.isDirectory(target)) {
                    new ProcessBuilder("explorer.exe", absPath).start();
                } else {
                    new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", absPath).start();
                }
            } catch (IOException ex) {
                Platform.runLater(() ->
                        publishStatus("Could not open " + target.getFileName() + ": " + ex.getMessage()));
            }
        });
    }

    private void loadItemIntoEditor(CourseItem item) {
        if (selectedItem != null && !selectedItem.equals(item)) {
            flushPendingEdits();
        }
        long requestId = previewRequestSeq.incrementAndGet();
        loadingEditor = true;
        try {
            selectedItem = item;
            pathHeader.setText(item.getRelativeDisplayPath());
            deleteItemButton.setDisable(false);
            openFileButton.setDisable(false);
            releasePreviewResources();

            if (item.isMarkdown()) {
                showEditorMode();
                editor.setDisable(true);
                saveButton.setDisable(true);
                editor.setText("Loading...");
                AppExecutors.ioExecutor().execute(() -> {
                    try {
                        String text = courseManager.readItem(item);
                        Platform.runLater(() -> {
                            if (previewRequestSeq.get() != requestId || !item.equals(selectedItem)) {
                                return;
                            }
                            loadingEditor = true;
                            try {
                                editor.setText(text);
                                editor.setDisable(false);
                                saveButton.setDisable(false);
                                setDirty(false);
                            } finally {
                                loadingEditor = false;
                            }
                        });
                    } catch (IOException ex) {
                        Platform.runLater(() -> {
                            if (previewRequestSeq.get() == requestId) {
                                publishStatus("Could not read file: " + ex.getMessage());
                            }
                        });
                    }
                });
            } else {
                saveButton.setDisable(true);
                editor.setDisable(true);
                loadNonMarkdownPreview(item, requestId);
            }
            setDirty(false);
        } catch (IOException ex) {
            publishStatus("Could not read file: " + ex.getMessage());
        } finally {
            loadingEditor = false;
        }
    }

    private void loadNonMarkdownPreview(CourseItem item, long requestId) throws IOException {
        Path filePath = item.getFilePath();
        CourseFilePreviewer.PreviewKind kind = previewer.detectKind(filePath);
        this.activePreviewKind = kind;

        switch (kind) {
            case PDF:
                showTextPreviewMode("Loading PDF pages...");
                requestPdfLazyPreview(item, requestId);
                break;
            case PPTX:
                showTextPreviewMode("Loading PowerPoint slides...");
                requestPptxLoad(item, requestId);
                break;
            case IMAGE:
                showTextPreviewMode("Loading image preview...");
                requestImagePreview(item, requestId);
                break;
            case TEXT:
                showTextPreviewMode("Loading text preview...");
                requestTextPreview(item, requestId);
                break;
            default:
                long bytes = Files.size(filePath);
                long kb = Math.max(1L, Math.round(bytes / 1024.0));
                showTextPreviewMode("Stored locally at:\n"
                        + filePath.toAbsolutePath() + "\n"
                        + "Size: " + kb + " KB\n\n"
                        + "No built-in preview for this file type.\n"
                        + "Click 'Open File' above (or double-click in the list) to open it externally.");
                break;
        }
    }

    private void requestPdfLazyPreview(CourseItem item, long requestId) {
        AppExecutors.renderExecutor().execute(() -> {
            try {
                PdfRenderService.PdfMetadata meta = previewer.inspectPdf(item.getFilePath());
                int total = meta.getPageCount();
                List<PdfPageSlot> slots = new ArrayList<>(total);
                for (int i = 0; i < total; i++) {
                    slots.add(new PdfPageSlot(item.getFilePath(), i, total, meta.getAspectRatio(i), requestId));
                }
                Platform.runLater(() -> {
                    if (previewRequestSeq.get() != requestId) {
                        return;
                    }
                    this.activePageIndex = 0;
                    this.activeTotalPages = total;
                    pdfPagesListView.getItems().setAll(slots);
                    showAllPdfPagesMode();
                    pdfPagesListView.scrollTo(0);
                    updatePreviewNavBar("Page 1 / " + total, 0, total);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (previewRequestSeq.get() != requestId) {
                        return;
                    }
                    String msg = ex.getMessage();
                    if (msg == null || msg.isBlank()) {
                        msg = "Could not preview PDF.";
                    }
                    hidePreviewNavBar();
                    showTextPreviewMode(msg + "\n\nClick 'Open File' above to open this PDF externally.");
                });
            }
        });
    }

    private void requestPptxLoad(CourseItem item, long requestId) {
        Task<CourseFilePreviewer.PptxPreview> task = new Task<>() {
            @Override
            protected CourseFilePreviewer.PptxPreview call() throws Exception {
                return previewer.extractPptx(item.getFilePath());
            }
        };
        task.setOnSucceeded(ev -> {
            if (previewRequestSeq.get() != requestId) {
                return;
            }
            CourseFilePreviewer.PptxPreview pptx = task.getValue();
            this.activePptxSlides = pptx.getSlides();
            this.activePageIndex = 0;
            this.activeTotalPages = activePptxSlides.size();

            if (pptx.getThumbnailBytes() != null) {
                Image thumb = new Image(new ByteArrayInputStream(pptx.getThumbnailBytes()), 320, 150, true, true);
                pptxThumbView.setImage(thumb);
                pptxThumbView.setVisible(true);
                pptxThumbView.setManaged(true);
            }
            showActivePptxSlide();
        });
        task.setOnFailed(ev -> {
            if (previewRequestSeq.get() != requestId) {
                return;
            }
            Throwable err = task.getException();
            String msg = "Could not extract slides from presentation.";
            if (err != null && err.getMessage() != null) {
                msg = msg + "\n" + err.getMessage();
            }
            hidePreviewNavBar();
            showTextPreviewMode(msg + "\n\nClick 'Open File' above to view this presentation in PowerPoint.");
        });
        CalendarService.backgroundExecutor().execute(task);
    }

    private void showActivePptxSlide() {
        if (activePptxSlides.isEmpty()) {
            return;
        }
        String text = activePptxSlides.get(activePageIndex);
        showTextPreviewMode(text);
        updatePreviewNavBar("Slide " + (activePageIndex + 1) + " / " + activePptxSlides.size(), activePageIndex, activePptxSlides.size());
    }

    private void requestImagePreview(CourseItem item, long requestId) {
        Task<Image> task = new Task<>() {
            @Override
            protected Image call() throws Exception {
                return loadBoundedImage(item.getFilePath(), 1200);
            }
        };
        task.setOnSucceeded(ev -> {
            if (previewRequestSeq.get() != requestId) {
                return;
            }
            previewImageView.setImage(task.getValue());
            showImagePreviewMode();
            updateImageFitWidth(imageScrollPane.getWidth());
        });
        task.setOnFailed(ev -> {
            if (previewRequestSeq.get() != requestId) {
                return;
            }
            showTextPreviewMode("Could not load image preview.\n\nClick 'Open File' above to open it externally.");
        });
        CalendarService.backgroundExecutor().execute(task);
    }

    private void requestTextPreview(CourseItem item, long requestId) {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return previewer.extractTextPreview(item.getFilePath());
            }
        };
        task.setOnSucceeded(ev -> {
            if (previewRequestSeq.get() != requestId) {
                return;
            }
            showTextPreviewMode(task.getValue());
        });
        task.setOnFailed(ev -> {
            if (previewRequestSeq.get() != requestId) {
                return;
            }
            showTextPreviewMode("Could not load text preview.\n\nClick 'Open File' above to open it externally.");
        });
        CalendarService.backgroundExecutor().execute(task);
    }

    private void stepPreviewPage(int delta) {
        if (selectedItem == null) {
            return;
        }
        if (activePreviewKind == CourseFilePreviewer.PreviewKind.PPTX && !activePptxSlides.isEmpty()) {
            int next = Math.max(0, Math.min(activePptxSlides.size() - 1, activePageIndex + delta));
            if (next != activePageIndex) {
                activePageIndex = next;
                showActivePptxSlide();
            }
        } else if (activePreviewKind == CourseFilePreviewer.PreviewKind.PDF && activeTotalPages > 0) {
            int next = Math.max(0, Math.min(activeTotalPages - 1, activePageIndex + delta));
            if (next != activePageIndex) {
                activePageIndex = next;
                pdfPagesListView.scrollTo(next);
                updatePreviewNavBar("Page " + (next + 1) + " / " + activeTotalPages, next, activeTotalPages);
            }
        }
    }

    private double computePdfSlotTargetWidth() {
        double viewportW = Math.max(260.0, pdfPagesListView.getWidth() - 46.0);
        if (pdfZoomFit) {
            return Math.min(760.0, viewportW);
        }
        return Math.max(260.0, Math.min(1800.0, 740.0 * pdfZoomFactor));
    }

    private void adjustPdfZoom(double delta) {
        if (activePreviewKind != CourseFilePreviewer.PreviewKind.PDF) {
            return;
        }
        if (pdfZoomFit) {
            pdfZoomFactor = Math.max(0.5, Math.min(2.5, computePdfSlotTargetWidth() / 740.0));
            pdfZoomFit = false;
        }
        pdfZoomFactor = Math.max(0.5, Math.min(2.5, pdfZoomFactor + delta));
        updateZoomReadout();
        pdfPagesListView.refresh();
    }

    private void resetPdfZoomToFit() {
        if (activePreviewKind != CourseFilePreviewer.PreviewKind.PDF) {
            return;
        }
        pdfZoomFit = true;
        pdfZoomFactor = 1.0;
        updateZoomReadout();
        pdfPagesListView.refresh();
    }

    private void updateZoomReadout() {
        if (pdfZoomFit) {
            zoomReadoutLabel.setText("Fit");
        } else {
            zoomReadoutLabel.setText(Math.round(pdfZoomFactor * 100.0) + "%");
        }
    }

    private Image loadBoundedImage(Path imagePath, int maxWidth) throws IOException {
        try (InputStream is = Files.newInputStream(imagePath)) {
            return new Image(is, maxWidth, 0, true, true);
        }
    }

    private void updateImageFitWidth(double viewportWidth) {
        Image img = previewImageView.getImage();
        if (img == null || viewportWidth <= 40.0) {
            previewImageView.setFitWidth(0.0);
            return;
        }
        double avail = viewportWidth - 28.0;
        if (activePreviewKind == CourseFilePreviewer.PreviewKind.PDF || img.getWidth() > avail) {
            previewImageView.setFitWidth(Math.max(200.0, avail));
        } else {
            previewImageView.setFitWidth(0.0);
        }
    }

    private void showContentNode(Region active) {
        Region[] nodes = new Region[]{editor, previewBox, imageScrollPane, pdfPagesListView};
        for (Region node : nodes) {
            boolean match = node == active;
            node.setVisible(match);
            node.setManaged(match);
        }
    }

    private void showEditorMode() {
        showContentNode(editor);
    }

    private void showTextPreviewMode(String text) {
        previewTextArea.setText(text);
        previewTextArea.positionCaret(0);
        showContentNode(previewBox);
    }

    private void showImagePreviewMode() {
        imageScrollPane.setVvalue(0.0);
        showContentNode(imageScrollPane);
    }

    private void showAllPdfPagesMode() {
        showContentNode(pdfPagesListView);
    }

    private void updatePreviewNavBar(String label, int index, int total) {
        pageIndicatorLabel.setText(label);
        setButtonVisible(prevPageButton, true);
        setButtonVisible(nextPageButton, true);
        prevPageButton.setDisable(index <= 0);
        nextPageButton.setDisable(index >= total - 1);

        boolean isPdf = activePreviewKind == CourseFilePreviewer.PreviewKind.PDF;
        setButtonVisible(zoomOutButton, isPdf);
        setButtonVisible(zoomInButton, isPdf);
        setButtonVisible(zoomFitButton, isPdf);
        zoomReadoutLabel.setVisible(isPdf);
        zoomReadoutLabel.setManaged(isPdf);
        if (isPdf) {
            updateZoomReadout();
        }

        previewNavBar.setVisible(true);
        previewNavBar.setManaged(true);
    }

    private void hidePreviewNavBar() {
        previewNavBar.setVisible(false);
        previewNavBar.setManaged(false);
        setButtonVisible(zoomOutButton, false);
        setButtonVisible(zoomInButton, false);
        setButtonVisible(zoomFitButton, false);
        zoomReadoutLabel.setVisible(false);
        zoomReadoutLabel.setManaged(false);
    }

    private void setButtonVisible(Button button, boolean visible) {
        button.setVisible(visible);
        button.setManaged(visible);
    }

    private void releasePreviewResources() {
        pdfPagesListView.getItems().clear();
        previewImageView.setImage(null);
        pptxThumbView.setImage(null);
        pptxThumbView.setVisible(false);
        pptxThumbView.setManaged(false);
        activePptxSlides = List.of();
        activePageIndex = 0;
        activeTotalPages = 0;
        previewer.clearPdfMemory();
        hidePreviewNavBar();
    }

    public void releaseTransientCaches() {
        previewRequestSeq.incrementAndGet();
        releasePreviewResources();
        if (selectedItem != null && !selectedItem.isMarkdown()) {
            previewTextArea.clear();
        }
    }

    public void ensureActivePreviewLoaded() {
        if (selectedItem != null && !selectedItem.isMarkdown()) {
            boolean emptyPreview = !pdfPagesListView.isVisible()
                    && !imageScrollPane.isVisible()
                    && (!previewBox.isVisible() || previewTextArea.getText().isEmpty());
            if (emptyPreview) {
                long requestId = previewRequestSeq.incrementAndGet();
                try {
                    loadNonMarkdownPreview(selectedItem, requestId);
                } catch (IOException ex) {
                    publishStatus("Could not read file: " + ex.getMessage());
                }
            }
        }
    }

    public void shutdownPreviewer() {
        previewRequestSeq.incrementAndGet();
        releasePreviewResources();
        previewer.cleanupAllPdfCaches();
    }

    private void clearEditor() {
        previewRequestSeq.incrementAndGet();
        loadingEditor = true;
        selectedItem = null;
        pathHeader.setText("No file selected");
        releasePreviewResources();
        showEditorMode();
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
        flushPendingEdits();
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
        flushPendingEdits();
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

    private static class ImportBatchResult {
        final int importedCount;
        final CourseItem lastImported;
        final List<String> errors;

        ImportBatchResult(int importedCount, CourseItem lastImported, List<String> errors) {
            this.importedCount = importedCount;
            this.lastImported = lastImported;
            this.errors = errors;
        }
    }

    private static class PdfPageSlot {
        final Path pdfPath;
        final int pageIndex;
        final int totalPages;
        final double aspectRatio;
        final long requestId;

        PdfPageSlot(Path pdfPath, int pageIndex, int totalPages, double aspectRatio, long requestId) {
            this.pdfPath = pdfPath;
            this.pageIndex = pageIndex;
            this.totalPages = totalPages;
            this.aspectRatio = aspectRatio;
            this.requestId = requestId;
        }
    }
}
