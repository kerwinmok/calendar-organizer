package com.calendarorganizer.course;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LectureRenderModel {

    public enum Scope {
        ALL_IN_COURSE("All in Course"),
        CURRENT_SECTION("Current Section"),
        SELECTED_FILE("Selected File");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum EntryKind {
        FILE_HEADER,
        PDF_PAGE,
        PPTX_SLIDE,
        IMAGE,
        TEXT_DOC,
        UNSUPPORTED
    }

    public static final class RenderEntry {
        private final CourseItem courseItem;
        private final EntryKind entryKind;
        private final int indexInFile;
        private final int totalInFile;
        private final double aspectRatio;
        private final String title;
        private final String detailText;

        public RenderEntry(CourseItem courseItem,
                           EntryKind entryKind,
                           int indexInFile,
                           int totalInFile,
                           double aspectRatio,
                           String title,
                           String detailText) {
            this.courseItem = courseItem;
            this.entryKind = entryKind;
            this.indexInFile = indexInFile;
            this.totalInFile = totalInFile;
            this.aspectRatio = aspectRatio;
            this.title = title;
            this.detailText = detailText;
        }

        public CourseItem getCourseItem() {
            return courseItem;
        }

        public EntryKind getEntryKind() {
            return entryKind;
        }

        public int getIndexInFile() {
            return indexInFile;
        }

        public int getTotalInFile() {
            return totalInFile;
        }

        public double getAspectRatio() {
            return aspectRatio;
        }

        public String getTitle() {
            return title;
        }

        public String getDetailText() {
            return detailText;
        }
    }

    public static final class RenderBuildResult {
        private final List<CourseItem> pickerItems;
        private final List<RenderEntry> entries;
        private final Map<CourseItem, Integer> itemStartIndices;
        private final int totalContentUnits;

        public RenderBuildResult(List<CourseItem> pickerItems,
                                 List<RenderEntry> entries,
                                 Map<CourseItem, Integer> itemStartIndices,
                                 int totalContentUnits) {
            this.pickerItems = pickerItems;
            this.entries = entries;
            this.itemStartIndices = itemStartIndices;
            this.totalContentUnits = totalContentUnits;
        }

        public List<CourseItem> getPickerItems() {
            return pickerItems;
        }

        public List<RenderEntry> getEntries() {
            return entries;
        }

        public Map<CourseItem, Integer> getItemStartIndices() {
            return itemStartIndices;
        }

        public int getTotalContentUnits() {
            return totalContentUnits;
        }
    }

    private LectureRenderModel() {
    }

    public static List<CourseItem> collectRenderableItems(CourseManager courseManager,
                                                          Course course,
                                                          CourseSection currentSection,
                                                          CourseItem selectedItem,
                                                          CourseSortOrder sortOrder,
                                                          Scope scope) throws IOException {
        if (course == null || courseManager == null) {
            return List.of();
        }
        Scope effectiveScope = scope != null ? scope : Scope.ALL_IN_COURSE;
        CourseSortOrder effectiveSort = sortOrder != null ? sortOrder : CourseSortOrder.NAME_ASC;

        switch (effectiveScope) {
            case SELECTED_FILE:
                if (selectedItem != null && Files.isRegularFile(selectedItem.getFilePath())) {
                    return List.of(selectedItem);
                }
                return List.of();
            case CURRENT_SECTION:
                if (currentSection == null) {
                    return List.of();
                }
                return courseManager.listItems(course, currentSection, effectiveSort);
            case ALL_IN_COURSE:
            default:
                List<CourseItem> all = new ArrayList<>();
                for (CourseSection section : CourseSection.values()) {
                    all.addAll(courseManager.listItems(course, section, effectiveSort));
                }
                return all;
        }
    }

    public static RenderBuildResult buildEntriesForItems(List<CourseItem> items, CourseFilePreviewer previewer) {
        if (items == null || items.isEmpty()) {
            return new RenderBuildResult(List.of(), List.of(), Map.of(), 0);
        }

        List<CourseItem> pickerItems = new ArrayList<>(items.size());
        List<RenderEntry> entries = new ArrayList<>();
        Map<CourseItem, Integer> startIndices = new LinkedHashMap<>();
        int totalUnits = 0;

        for (CourseItem item : items) {
            Path path = item.getFilePath();
            if (path == null || !Files.isRegularFile(path)) {
                continue;
            }
            pickerItems.add(item);
            startIndices.put(item, entries.size());
            entries.add(new RenderEntry(
                    item,
                    EntryKind.FILE_HEADER,
                    0,
                    1,
                    0.0,
                    item.getFileName(),
                    item.getSection().getLabel()
            ));

            if (item.isMarkdown()) {
                entries.add(new RenderEntry(
                        item,
                        EntryKind.TEXT_DOC,
                        0,
                        1,
                        0.0,
                        item.getFileName(),
                        null
                ));
                totalUnits++;
                continue;
            }

            CourseFilePreviewer.PreviewKind kind = previewer.detectKind(path);
            switch (kind) {
                case PDF:
                    totalUnits += appendPdfEntries(item, path, previewer, entries);
                    break;
                case PPTX:
                    totalUnits += appendPptxEntries(item, path, previewer, entries);
                    break;
                case IMAGE:
                    entries.add(new RenderEntry(item, EntryKind.IMAGE, 0, 1, 0.75, item.getFileName(), null));
                    totalUnits++;
                    break;
                case TEXT:
                    entries.add(new RenderEntry(item, EntryKind.TEXT_DOC, 0, 1, 0.0, item.getFileName(), null));
                    totalUnits++;
                    break;
                default:
                    entries.add(new RenderEntry(
                            item,
                            EntryKind.UNSUPPORTED,
                            0,
                            1,
                            0.0,
                            item.getFileName(),
                            buildUnsupportedSummary(path)
                    ));
                    totalUnits++;
                    break;
            }
        }

        return new RenderBuildResult(pickerItems, entries, startIndices, totalUnits);
    }

    private static int appendPdfEntries(CourseItem item,
                                        Path path,
                                        CourseFilePreviewer previewer,
                                        List<RenderEntry> entries) {
        try {
            PdfRenderService.PdfMetadata meta = previewer.inspectPdf(path);
            int total = meta.getPageCount();
            for (int i = 0; i < total; i++) {
                entries.add(new RenderEntry(
                        item,
                        EntryKind.PDF_PAGE,
                        i,
                        total,
                        meta.getAspectRatio(i),
                        item.getFileName() + " — Page " + (i + 1) + " / " + total,
                        null
                ));
            }
            return total;
        } catch (IOException ex) {
            entries.add(new RenderEntry(
                    item,
                    EntryKind.UNSUPPORTED,
                    0,
                    1,
                    0.0,
                    item.getFileName(),
                    "Could not inspect PDF (" + ex.getMessage() + "). Click Open Externally."
            ));
            return 1;
        }
    }

    private static int appendPptxEntries(CourseItem item,
                                         Path path,
                                         CourseFilePreviewer previewer,
                                         List<RenderEntry> entries) {
        try {
            CourseFilePreviewer.PptxPreview pptx = previewer.extractPptx(path);
            List<String> slides = pptx.getSlides();
            int total = slides.size();
            for (int i = 0; i < total; i++) {
                entries.add(new RenderEntry(
                        item,
                        EntryKind.PPTX_SLIDE,
                        i,
                        total,
                        0.0,
                        item.getFileName() + " — Slide " + (i + 1) + " / " + total,
                        slides.get(i)
                ));
            }
            return total;
        } catch (IOException ex) {
            entries.add(new RenderEntry(
                    item,
                    EntryKind.UNSUPPORTED,
                    0,
                    1,
                    0.0,
                    item.getFileName(),
                    "Could not extract slides (" + ex.getMessage() + "). Click Open Externally."
            ));
            return 1;
        }
    }

    private static String buildUnsupportedSummary(Path path) {
        try {
            long kb = Math.max(1L, Math.round(Files.size(path) / 1024.0));
            return "File size: " + kb + " KB — click Open Externally to view in default app.";
        } catch (IOException ex) {
            return "Click Open Externally to view in default app.";
        }
    }
}
