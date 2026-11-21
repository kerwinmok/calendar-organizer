package com.calendarorganizer.ui;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseFilePreviewer;
import com.calendarorganizer.course.CourseItem;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.course.CourseSection;
import com.calendarorganizer.course.CourseSortOrder;
import com.calendarorganizer.course.LectureRenderModel;
import com.calendarorganizer.service.AppExecutors;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.util.Duration;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class LectureRenderPane extends BorderPane {

    private static final double MIN_ZOOM = 0.5;
    private static final double MAX_ZOOM = 2.5;
    private static final double ZOOM_STEP = 0.15;
    private static final double BASE_ZOOM_WIDTH = 720.0;
    private static final int MAX_TEXT_CACHE_ENTRIES = 8;
    private static final int MAX_IMAGE_CACHE_ENTRIES = 6;

    private final CourseManager courseManager;
    private final CourseFilePreviewer previewer;
    private final AtomicLong loadSeq = new AtomicLong(0L);

    private final Map<Path, String> textCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, String> eldest) {
                    return size() > MAX_TEXT_CACHE_ENTRIES;
                }
            }
    );

    private final Map<Path, Image> imageCache = Collections.synchronizedMap(
            new LinkedHashMap<>(12, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, Image> eldest) {
                    return size() > MAX_IMAGE_CACHE_ENTRIES;
                }
            }
    );

    private final Label titleLabel = new Label("Lecture Reader");
    private final ComboBox<LectureRenderModel.Scope> scopeBox = new ComboBox<>();
    private final Button refreshBtn = new Button("Refresh");
    private final Button fullScreenBtn = new Button("Full Screen");
    private final Button closeBtn = new Button("Close");

    private final Button prevFileBtn = new Button("<");
    private final ComboBox<CourseItem> filePickerBox = new ComboBox<>();
    private final Button nextFileBtn = new Button(">");
    private final Label summaryLabel = new Label("");
    private final Button zoomOutBtn = new Button("-");
    private final Label zoomReadoutLabel = new Label("Fit");
    private final Button zoomInBtn = new Button("+");
    private final Button zoomFitBtn = new Button("Fit");

    private final ListView<LectureRenderModel.RenderEntry> entriesListView = new ListView<>();
    private final Label emptyPlaceholder = new Label("Select a course and click Render to view lectures.");
    private final Map<RenderCell, Boolean> activeCells = new WeakHashMap<>();

    private Course currentCourse;
    private CourseSection currentSection = CourseSection.LECTURES;
    private CourseItem preferredItem;
    private CourseSortOrder currentSortOrder = CourseSortOrder.NAME_ASC;
    private List<CourseItem> pickerItems = List.of();
    private Map<CourseItem, Integer> itemStartIndices = Map.of();

    private boolean zoomFit = true;
    private double zoomFactor = 1.0;
    private boolean updatingPicker;
    private boolean syncScheduled;
    private boolean fullScreenActive;

    private Consumer<Boolean> onFullScreenToggle;
    private Runnable onCloseRequested;
    private Consumer<String> onStatusChanged;

    public LectureRenderPane(CourseManager courseManager, CourseFilePreviewer previewer) {
        this.courseManager = courseManager;
        this.previewer = previewer;
        getStyleClass().add("render-pane");

        setTop(buildToolbar());
        configureListView();
        setCenter(entriesListView);
    }

    private VBox buildToolbar() {
        titleLabel.getStyleClass().add("section-heading");

        scopeBox.getItems().setAll(LectureRenderModel.Scope.values());
        scopeBox.setValue(LectureRenderModel.Scope.ALL_IN_COURSE);
        scopeBox.setOnAction(e -> reloadCurrentContext());

        refreshBtn.setOnAction(e -> reloadCurrentContext());
        fullScreenBtn.setOnAction(e -> {
            if (onFullScreenToggle != null) {
                onFullScreenToggle.accept(!fullScreenActive);
            }
        });
        closeBtn.setOnAction(e -> {
            if (onCloseRequested != null) {
                onCloseRequested.run();
            }
        });

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(8, titleLabel, scopeBox, refreshBtn, topSpacer, fullScreenBtn, closeBtn);
        headerRow.getStyleClass().add("lc-subbar");
        headerRow.setAlignment(Pos.CENTER_LEFT);

        prevFileBtn.setOnAction(e -> stepPickerFile(-1));
        nextFileBtn.setOnAction(e -> stepPickerFile(1));

        filePickerBox.setMaxWidth(Double.MAX_VALUE);
        filePickerBox.setPrefWidth(250);
        HBox.setHgrow(filePickerBox, Priority.ALWAYS);
        filePickerBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(CourseItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText("[" + item.getSection().getLabel() + "] " + item.getFileName());
                }
            }
        });
        filePickerBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(CourseItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("No files");
                } else {
                    setText("[" + item.getSection().getLabel() + "] " + item.getFileName());
                }
            }
        });
        filePickerBox.setOnAction(e -> {
            if (updatingPicker) {
                return;
            }
            CourseItem chosen = filePickerBox.getValue();
            if (chosen != null) {
                jumpToCourseItem(chosen);
            }
        });

        summaryLabel.getStyleClass().add("dim-label");
        zoomOutBtn.setOnAction(e -> adjustZoom(-ZOOM_STEP));
        zoomInBtn.setOnAction(e -> adjustZoom(ZOOM_STEP));
        zoomFitBtn.setOnAction(e -> resetZoomToFit());
        zoomReadoutLabel.getStyleClass().add("dim-label");
        zoomReadoutLabel.setMinWidth(40);
        zoomReadoutLabel.setAlignment(Pos.CENTER);

        HBox pickerRow = new HBox(
                6,
                prevFileBtn,
                filePickerBox,
                nextFileBtn,
                summaryLabel,
                zoomOutBtn,
                zoomReadoutLabel,
                zoomInBtn,
                zoomFitBtn
        );
        pickerRow.getStyleClass().add("lc-subbar");
        pickerRow.setAlignment(Pos.CENTER_LEFT);

        updatePickerButtons();
        updateZoomReadout();
        return new VBox(headerRow, pickerRow);
    }

    private void configureListView() {
        entriesListView.getStyleClass().add("pdf-pages-list");
        emptyPlaceholder.getStyleClass().add("dim-label");
        entriesListView.setPlaceholder(emptyPlaceholder);
        entriesListView.setCellFactory(list -> {
            RenderCell cell = new RenderCell();
            activeCells.put(cell, Boolean.TRUE);
            return cell;
        });

        entriesListView.addEventFilter(ScrollEvent.SCROLL, ev -> {
            if (ev.isControlDown() && ev.getDeltaY() != 0.0) {
                adjustZoom(ev.getDeltaY() > 0 ? ZOOM_STEP : -ZOOM_STEP);
                ev.consume();
                return;
            }
            scheduleVisiblePickerSync();
        });
        entriesListView.setOnKeyReleased(ev -> scheduleVisiblePickerSync());
        entriesListView.setOnMouseReleased(ev -> scheduleVisiblePickerSync());

        PauseTransition resizeDebounce = new PauseTransition(Duration.millis(120));
        resizeDebounce.setOnFinished(e -> {
            if (zoomFit && !entriesListView.getItems().isEmpty()) {
                entriesListView.refresh();
            }
        });
        entriesListView.widthProperty().addListener((obs, oldW, newW) -> {
            if (zoomFit && !entriesListView.getItems().isEmpty()) {
                resizeDebounce.playFromStart();
            }
        });
    }

    public void setOnFullScreenToggle(Consumer<Boolean> listener) {
        this.onFullScreenToggle = listener;
    }

    public void setOnCloseRequested(Runnable listener) {
        this.onCloseRequested = listener;
    }

    public void setOnStatusChanged(Consumer<String> listener) {
        this.onStatusChanged = listener;
    }

    public void setFullScreenState(boolean fullScreen) {
        this.fullScreenActive = fullScreen;
        fullScreenBtn.setText(fullScreen ? "Exit Full Screen" : "Full Screen");
    }

    public boolean isFullScreenActive() {
        return fullScreenActive;
    }

    public void loadCourseContext(Course course,
                                  CourseSection section,
                                  CourseItem selectedItem,
                                  CourseSortOrder sortOrder) {
        this.currentCourse = course;
        this.currentSection = section != null ? section : CourseSection.LECTURES;
        this.preferredItem = selectedItem;
        this.currentSortOrder = sortOrder != null ? sortOrder : CourseSortOrder.NAME_ASC;

        if (course != null) {
            titleLabel.setText(course.getDisplayName());
        } else {
            titleLabel.setText("Lecture Reader");
        }
        reloadCurrentContext();
    }

    public void reloadCurrentContext() {
        long seq = loadSeq.incrementAndGet();
        clearLocalTransientCaches();

        if (currentCourse == null) {
            entriesListView.getItems().clear();
            emptyPlaceholder.setText("Select a course first to render its files.");
            setPickerItems(List.of(), Map.of(), 0);
            return;
        }

        Course targetCourse = currentCourse;
        CourseSection targetSection = currentSection;
        CourseItem targetPreferred = preferredItem;
        CourseSortOrder targetSort = currentSortOrder;
        LectureRenderModel.Scope targetScope = scopeBox.getValue() != null
                ? scopeBox.getValue()
                : LectureRenderModel.Scope.ALL_IN_COURSE;

        entriesListView.getItems().clear();
        emptyPlaceholder.setText("Preparing lecture files for " + targetCourse.getDisplayName() + "...");
        summaryLabel.setText("Loading...");

        AppExecutors.ioExecutor().execute(() -> {
            try {
                List<CourseItem> items = LectureRenderModel.collectRenderableItems(
                        courseManager,
                        targetCourse,
                        targetSection,
                        targetPreferred,
                        targetSort,
                        targetScope
                );
                LectureRenderModel.RenderBuildResult result = LectureRenderModel.buildEntriesForItems(items, previewer);
                Platform.runLater(() -> {
                    if (loadSeq.get() != seq) {
                        return;
                    }
                    applyBuildResult(result, targetPreferred);
                });
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    if (loadSeq.get() != seq) {
                        return;
                    }
                    entriesListView.getItems().clear();
                    emptyPlaceholder.setText("Could not load files: " + ex.getMessage());
                    setPickerItems(List.of(), Map.of(), 0);
                    publishStatus("Render failed: " + ex.getMessage());
                });
            }
        });
    }

    private void applyBuildResult(LectureRenderModel.RenderBuildResult result, CourseItem scrollTarget) {
        if (result.getEntries().isEmpty()) {
            entriesListView.getItems().clear();
            emptyPlaceholder.setText("No lecture files in this selection.");
            setPickerItems(List.of(), Map.of(), 0);
            return;
        }

        setPickerItems(result.getPickerItems(), result.getItemStartIndices(), result.getTotalContentUnits());
        entriesListView.getItems().setAll(result.getEntries());

        CourseItem target = scrollTarget;
        if (target == null || !result.getItemStartIndices().containsKey(target)) {
            target = result.getPickerItems().get(0);
        }
        selectPickerItemSilently(target);
        Integer startIdx = result.getItemStartIndices().get(target);
        if (startIdx != null) {
            entriesListView.scrollTo(startIdx);
        }
        publishStatus("Rendered " + result.getPickerItems().size() + " file(s) (" + result.getTotalContentUnits() + " page/item units).");
    }

    private void setPickerItems(List<CourseItem> items, Map<CourseItem, Integer> startIndices, int totalUnits) {
        this.pickerItems = items;
        this.itemStartIndices = startIndices;
        updatingPicker = true;
        try {
            filePickerBox.getItems().setAll(items);
            if (!items.isEmpty()) {
                filePickerBox.getSelectionModel().selectFirst();
            } else {
                filePickerBox.getSelectionModel().clearSelection();
            }
        } finally {
            updatingPicker = false;
        }
        if (items.isEmpty()) {
            summaryLabel.setText("0 files");
        } else {
            summaryLabel.setText(items.size() + " file(s) · " + totalUnits + " units");
        }
        updatePickerButtons();
    }

    private void selectPickerItemSilently(CourseItem item) {
        updatingPicker = true;
        try {
            filePickerBox.getSelectionModel().select(item);
        } finally {
            updatingPicker = false;
        }
        updatePickerButtons();
    }

    private void stepPickerFile(int delta) {
        if (pickerItems.isEmpty()) {
            return;
        }
        CourseItem current = filePickerBox.getValue();
        int currentIndex = current != null ? pickerItems.indexOf(current) : 0;
        if (currentIndex < 0) {
            currentIndex = 0;
        }
        int nextIndex = Math.max(0, Math.min(pickerItems.size() - 1, currentIndex + delta));
        if (nextIndex != currentIndex) {
            CourseItem target = pickerItems.get(nextIndex);
            selectPickerItemSilently(target);
            jumpToCourseItem(target);
        }
    }

    private void jumpToCourseItem(CourseItem item) {
        preferredItem = item;
        Integer entryIndex = itemStartIndices.get(item);
        if (entryIndex != null && entryIndex >= 0 && entryIndex < entriesListView.getItems().size()) {
            entriesListView.scrollTo(entryIndex);
        }
        updatePickerButtons();
    }

    private void updatePickerButtons() {
        int count = pickerItems.size();
        CourseItem current = filePickerBox.getValue();
        int idx = current != null ? pickerItems.indexOf(current) : -1;
        prevFileBtn.setDisable(count <= 1 || idx <= 0);
        nextFileBtn.setDisable(count <= 1 || idx >= count - 1);
        filePickerBox.setDisable(count == 0);
    }

    private void scheduleVisiblePickerSync() {
        if (syncScheduled || pickerItems.isEmpty()) {
            return;
        }
        syncScheduled = true;
        Platform.runLater(() -> {
            syncScheduled = false;
            syncPickerToTopVisibleCell();
        });
    }

    private void syncPickerToTopVisibleCell() {
        Bounds listBounds = entriesListView.localToScene(entriesListView.getBoundsInLocal());
        if (listBounds == null) {
            return;
        }
        double topThreshold = listBounds.getMinY() + 8.0;
        int bestIndex = Integer.MAX_VALUE;
        LectureRenderModel.RenderEntry bestEntry = null;

        for (RenderCell cell : activeCells.keySet()) {
            if (cell == null || cell.isEmpty() || cell.getItem() == null || !cell.isVisible()) {
                continue;
            }
            Bounds cellBounds = cell.localToScene(cell.getBoundsInLocal());
            if (cellBounds != null && cellBounds.getMaxY() > topThreshold) {
                int idx = cell.getIndex();
                if (idx >= 0 && idx < bestIndex) {
                    bestIndex = idx;
                    bestEntry = cell.getItem();
                }
            }
        }

        if (bestEntry != null && !bestEntry.getCourseItem().equals(filePickerBox.getValue())) {
            selectPickerItemSilently(bestEntry.getCourseItem());
        }
    }

    private double computeTargetWidth() {
        double viewportW = Math.max(260.0, entriesListView.getWidth() - 56.0);
        if (zoomFit) {
            return Math.min(760.0, viewportW);
        }
        return Math.max(260.0, Math.min(1800.0, BASE_ZOOM_WIDTH * zoomFactor));
    }

    private void adjustZoom(double delta) {
        if (zoomFit) {
            zoomFactor = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, computeTargetWidth() / BASE_ZOOM_WIDTH));
            zoomFit = false;
        }
        zoomFactor = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoomFactor + delta));
        updateZoomReadout();
        entriesListView.refresh();
    }

    private void resetZoomToFit() {
        zoomFit = true;
        zoomFactor = 1.0;
        updateZoomReadout();
        entriesListView.refresh();
    }

    private void updateZoomReadout() {
        if (zoomFit) {
            zoomReadoutLabel.setText("Fit");
        } else {
            zoomReadoutLabel.setText(Math.round(zoomFactor * 100.0) + "%");
        }
    }

    private void clearLocalTransientCaches() {
        textCache.clear();
        imageCache.clear();
    }

    public void releaseResources() {
        loadSeq.incrementAndGet();
        entriesListView.getItems().clear();
        clearLocalTransientCaches();
        setPickerItems(List.of(), Map.of(), 0);
        previewer.clearPdfMemory();
    }

    private void openExternally(Path path) {
        if (path == null || !Files.exists(path)) {
            publishStatus("File does not exist.");
            return;
        }
        AppExecutors.ioExecutor().execute(() -> {
            try {
                new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", path.toAbsolutePath().toString()).start();
            } catch (IOException ex) {
                Platform.runLater(() -> publishStatus("Could not open file: " + ex.getMessage()));
            }
        });
    }

    private void publishStatus(String message) {
        if (onStatusChanged != null) {
            onStatusChanged.accept(message);
        }
    }

    private final class RenderCell extends ListCell<LectureRenderModel.RenderEntry> {
        private final VBox container = new VBox(6);

        private final Label sectionPill = new Label();
        private final Label headerTitleLabel = new Label();
        private final Button openExtBtn = new Button("Open Externally");
        private final HBox headerBanner = new HBox(8, sectionPill, headerTitleLabel, new Region(), openExtBtn);

        private final Label pageTitleLabel = new Label();
        private final Label pageLoadingHint = new Label();
        private final ImageView pageImageView = new ImageView();
        private final StackPane pageFrame = new StackPane(pageLoadingHint, pageImageView);
        private final VBox pageCard = new VBox(4, pageTitleLabel, pageFrame);

        private final Label textTitleLabel = new Label();
        private final Label textBodyLabel = new Label();
        private final VBox textCard = new VBox(6, textTitleLabel, textBodyLabel);

        private long cellTicket;
        private LectureRenderModel.RenderEntry boundEntry;
        private int boundRenderWidth;
        private long boundSeq;

        RenderCell() {
            sectionPill.getStyleClass().add("render-section-pill");
            headerTitleLabel.getStyleClass().add("section-heading");
            HBox.setHgrow(headerBanner.getChildren().get(2), Priority.ALWAYS);
            headerBanner.getStyleClass().add("render-file-banner");
            headerBanner.setAlignment(Pos.CENTER_LEFT);

            pageTitleLabel.getStyleClass().add("dim-label");
            pageLoadingHint.getStyleClass().add("dim-label");
            pageImageView.setPreserveRatio(true);
            pageImageView.setSmooth(true);
            pageFrame.getStyleClass().add("pdf-page-frame");
            pageFrame.setAlignment(Pos.CENTER);
            pageCard.setAlignment(Pos.TOP_CENTER);

            textTitleLabel.getStyleClass().add("dim-label");
            textBodyLabel.getStyleClass().add("render-text-body");
            textBodyLabel.setWrapText(true);
            textCard.getStyleClass().add("render-text-card");

            container.setAlignment(Pos.TOP_CENTER);
            container.getChildren().setAll(headerBanner, pageCard, textCard);

            visibleProperty().addListener((obs, wasVisible, isNowVisible) -> {
                if (!isNowVisible) {
                    cellTicket++;
                    pageImageView.setImage(null);
                } else if (boundEntry != null && !isEmpty()) {
                    loadVisibleContentIfNeeded();
                }
            });
            parentProperty().addListener((obs, oldParent, newParent) -> {
                if (newParent == null) {
                    cellTicket++;
                    pageImageView.setImage(null);
                }
            });
        }

        @Override
        protected void updateItem(LectureRenderModel.RenderEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            cellTicket++;
            if (empty || entry == null) {
                boundEntry = null;
                boundRenderWidth = 0;
                pageImageView.setImage(null);
                setGraphic(null);
                return;
            }

            double targetWidth = computeTargetWidth();
            container.setMaxWidth(targetWidth + 24.0);
            long seqSnapshot = loadSeq.get();
            boundEntry = entry;
            boundRenderWidth = (int) Math.round(targetWidth);
            boundSeq = seqSnapshot;

            switch (entry.getEntryKind()) {
                case FILE_HEADER:
                    pageImageView.setImage(null);
                    showCard(headerBanner);
                    headerBanner.setPrefWidth(targetWidth);
                    headerBanner.setMaxWidth(targetWidth);
                    sectionPill.setText(entry.getDetailText());
                    headerTitleLabel.setText(entry.getTitle());
                    Path filePath = entry.getCourseItem().getFilePath();
                    openExtBtn.setOnAction(e -> openExternally(filePath));
                    break;
                case PDF_PAGE:
                    showCard(pageCard);
                    double pdfHeight = Math.max(120.0, targetWidth * entry.getAspectRatio());
                    pageFrame.setMinSize(targetWidth, pdfHeight);
                    pageFrame.setPrefSize(targetWidth, pdfHeight);
                    pageFrame.setMaxSize(targetWidth, pdfHeight);
                    pageImageView.setFitWidth(targetWidth);
                    pageImageView.setFitHeight(pdfHeight);
                    pageTitleLabel.setText(entry.getTitle());

                    WritableImage cached = previewer.getCachedPdfPageFxImage(
                            entry.getCourseItem().getFilePath(),
                            entry.getIndexInFile(),
                            boundRenderWidth
                    );
                    if (cached != null) {
                        pageLoadingHint.setVisible(false);
                        pageImageView.setImage(cached);
                    } else {
                        pageImageView.setImage(null);
                        pageLoadingHint.setText("Rendering page " + (entry.getIndexInFile() + 1) + "...");
                        pageLoadingHint.setVisible(true);
                        if (isVisible()) {
                            loadVisibleContentIfNeeded();
                        }
                    }
                    break;
                case IMAGE:
                    showCard(pageCard);
                    double imgBoxHeight = Math.max(180.0, targetWidth * 0.65);
                    pageFrame.setMinSize(targetWidth, imgBoxHeight);
                    pageFrame.setPrefSize(targetWidth, Region.USE_COMPUTED_SIZE);
                    pageFrame.setMaxSize(targetWidth, Region.USE_PREF_SIZE);
                    pageImageView.setFitWidth(targetWidth);
                    pageImageView.setFitHeight(0.0);
                    pageTitleLabel.setText(entry.getTitle());

                    Image cachedImg = imageCache.get(entry.getCourseItem().getFilePath());
                    if (cachedImg != null) {
                        pageLoadingHint.setVisible(false);
                        pageImageView.setImage(cachedImg);
                    } else {
                        pageImageView.setImage(null);
                        pageLoadingHint.setText("Loading image...");
                        pageLoadingHint.setVisible(true);
                        if (isVisible()) {
                            loadVisibleContentIfNeeded();
                        }
                    }
                    break;
                case PPTX_SLIDE:
                    pageImageView.setImage(null);
                    showCard(textCard);
                    configureTextCardGeometry(targetWidth);
                    textTitleLabel.setText(entry.getTitle());
                    textBodyLabel.setText(entry.getDetailText() != null ? entry.getDetailText() : "");
                    break;
                case TEXT_DOC:
                    pageImageView.setImage(null);
                    showCard(textCard);
                    configureTextCardGeometry(targetWidth);
                    textTitleLabel.setText(entry.getTitle());

                    String cachedText = textCache.get(entry.getCourseItem().getFilePath());
                    if (cachedText != null) {
                        textBodyLabel.setText(cachedText);
                    } else {
                        textBodyLabel.setText("Loading text...");
                        if (isVisible()) {
                            loadVisibleContentIfNeeded();
                        }
                    }
                    break;
                case UNSUPPORTED:
                default:
                    pageImageView.setImage(null);
                    showCard(textCard);
                    configureTextCardGeometry(targetWidth);
                    textTitleLabel.setText(entry.getTitle());
                    textBodyLabel.setText(entry.getDetailText() != null ? entry.getDetailText() : "");
                    break;
            }

            setGraphic(container);
        }

        private void loadVisibleContentIfNeeded() {
            LectureRenderModel.RenderEntry entry = boundEntry;
            int renderWidth = boundRenderWidth;
            long seqSnapshot = boundSeq;
            if (entry == null || loadSeq.get() != seqSnapshot) {
                return;
            }
            switch (entry.getEntryKind()) {
                case PDF_PAGE:
                    if (pageImageView.getImage() != null || renderWidth <= 0) {
                        return;
                    }
                    Path pdfPath = entry.getCourseItem().getFilePath();
                    WritableImage cached = previewer.getCachedPdfPageFxImage(pdfPath, entry.getIndexInFile(), renderWidth);
                    if (cached != null) {
                        pageLoadingHint.setVisible(false);
                        pageImageView.setImage(cached);
                        return;
                    }
                    long pdfTicket = ++cellTicket;
                    AppExecutors.renderExecutor().execute(() -> {
                        if (loadSeq.get() != seqSnapshot || cellTicket != pdfTicket || !isVisible()) {
                            return;
                        }
                        try {
                            WritableImage img = previewer.renderPdfPageFxImage(pdfPath, entry.getIndexInFile(), renderWidth);
                            Platform.runLater(() -> {
                                if (cellTicket == pdfTicket
                                        && loadSeq.get() == seqSnapshot
                                        && !isEmpty()
                                        && isVisible()
                                        && getItem() == entry) {
                                    pageLoadingHint.setVisible(false);
                                    pageImageView.setImage(img);
                                }
                            });
                        } catch (Throwable ex) {
                            Platform.runLater(() -> {
                                if (cellTicket == pdfTicket
                                        && loadSeq.get() == seqSnapshot
                                        && !isEmpty()
                                        && getItem() == entry) {
                                    pageLoadingHint.setText("Could not render page " + (entry.getIndexInFile() + 1));
                                    pageLoadingHint.setVisible(true);
                                }
                            });
                        }
                    });
                    break;
                case IMAGE:
                    if (pageImageView.getImage() != null) {
                        return;
                    }
                    Path imgPath = entry.getCourseItem().getFilePath();
                    Image cachedImg = imageCache.get(imgPath);
                    if (cachedImg != null) {
                        pageLoadingHint.setVisible(false);
                        pageImageView.setImage(cachedImg);
                        return;
                    }
                    long imgTicket = ++cellTicket;
                    AppExecutors.renderExecutor().execute(() -> {
                        if (loadSeq.get() != seqSnapshot || cellTicket != imgTicket || !isVisible()) {
                            return;
                        }
                        try (InputStream is = Files.newInputStream(imgPath)) {
                            Image loaded = new Image(is, 1000, 0, true, true);
                            imageCache.put(imgPath, loaded);
                            Platform.runLater(() -> {
                                if (cellTicket == imgTicket
                                        && loadSeq.get() == seqSnapshot
                                        && !isEmpty()
                                        && isVisible()
                                        && getItem() == entry) {
                                    pageLoadingHint.setVisible(false);
                                    pageImageView.setImage(loaded);
                                }
                            });
                        } catch (IOException ex) {
                            Platform.runLater(() -> {
                                if (cellTicket == imgTicket
                                        && loadSeq.get() == seqSnapshot
                                        && !isEmpty()
                                        && getItem() == entry) {
                                    pageLoadingHint.setText("Could not load image.");
                                    pageLoadingHint.setVisible(true);
                                }
                            });
                        }
                    });
                    break;
                case TEXT_DOC:
                    Path textPath = entry.getCourseItem().getFilePath();
                    String cachedText = textCache.get(textPath);
                    if (cachedText != null) {
                        textBodyLabel.setText(cachedText);
                        return;
                    }
                    long textTicket = ++cellTicket;
                    AppExecutors.ioExecutor().execute(() -> {
                        if (loadSeq.get() != seqSnapshot || cellTicket != textTicket || !isVisible()) {
                            return;
                        }
                        try {
                            String content = previewer.extractTextPreview(textPath);
                            textCache.put(textPath, content);
                            Platform.runLater(() -> {
                                if (cellTicket == textTicket
                                        && loadSeq.get() == seqSnapshot
                                        && !isEmpty()
                                        && isVisible()
                                        && getItem() == entry) {
                                    textBodyLabel.setText(content);
                                }
                            });
                        } catch (IOException ex) {
                            Platform.runLater(() -> {
                                if (cellTicket == textTicket
                                        && loadSeq.get() == seqSnapshot
                                        && !isEmpty()
                                        && getItem() == entry) {
                                    textBodyLabel.setText("Could not read file: " + ex.getMessage());
                                }
                            });
                        }
                    });
                    break;
                default:
                    break;
            }
        }

        private void configureTextCardGeometry(double targetWidth) {
            textCard.setMinWidth(targetWidth);
            textCard.setPrefWidth(targetWidth);
            textCard.setMaxWidth(targetWidth);
            textBodyLabel.setMaxWidth(Math.max(200.0, targetWidth - 28.0));
            double fontPx = zoomFit ? 13.5 : Math.max(10.0, Math.min(24.0, 13.5 * zoomFactor));
            textBodyLabel.setStyle("-fx-font-size: " + String.format("%.1f", fontPx) + "px;");
        }

        private void showCard(Region active) {
            Region[] cards = new Region[]{headerBanner, pageCard, textCard};
            for (Region card : cards) {
                boolean match = card == active;
                card.setVisible(match);
                card.setManaged(match);
            }
        }
    }
}
