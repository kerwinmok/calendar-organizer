package com.calendarorganizer.course;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LectureRenderModelTest {

    private final CourseFilePreviewer previewer = new CourseFilePreviewer();

    @AfterEach
    void tearDown() {
        previewer.cleanupAllPdfCaches();
    }

    @Test
    void collectsAndBuildsEntriesAcrossCourseScopes(@TempDir Path tempDir) throws IOException {
        CourseManager manager = new CourseManager(tempDir);
        Course course = manager.createCourse("CMPT 201");

        Path srcPdf = createSamplePdf(tempDir.resolve("lec01.pdf"), 3);
        Path srcPptx = createSamplePptx(tempDir.resolve("slides02.pptx"), List.of("Intro Slide", "Topic Slide"));
        CourseItem pdfItem = manager.importExternalFile(course, CourseSection.LECTURES, srcPdf);
        CourseItem pptxItem = manager.importExternalFile(course, CourseSection.LECTURES, srcPptx);
        CourseItem noteItem = manager.createItem(course, CourseSection.NOTES, "summary", "# Summary\n\nKey ideas.");

        List<CourseItem> allItems = LectureRenderModel.collectRenderableItems(
                manager,
                course,
                CourseSection.LECTURES,
                pdfItem,
                CourseSortOrder.NAME_ASC,
                LectureRenderModel.Scope.ALL_IN_COURSE
        );
        assertEquals(3, allItems.size());

        List<CourseItem> lectureOnly = LectureRenderModel.collectRenderableItems(
                manager,
                course,
                CourseSection.LECTURES,
                pdfItem,
                CourseSortOrder.NAME_ASC,
                LectureRenderModel.Scope.CURRENT_SECTION
        );
        assertEquals(2, lectureOnly.size());

        List<CourseItem> selectedOnly = LectureRenderModel.collectRenderableItems(
                manager,
                course,
                CourseSection.LECTURES,
                noteItem,
                CourseSortOrder.NAME_ASC,
                LectureRenderModel.Scope.SELECTED_FILE
        );
        assertEquals(List.of(noteItem), selectedOnly);

        LectureRenderModel.RenderBuildResult built = LectureRenderModel.buildEntriesForItems(allItems, previewer);
        assertEquals(3, built.getPickerItems().size());
        assertEquals(6, built.getTotalContentUnits()); // 3 pdf pages + 2 pptx slides + 1 md note
        assertEquals(9, built.getEntries().size()); // 3 headers + 6 content entries
        assertEquals(0, built.getItemStartIndices().get(pdfItem));
        assertTrue(built.getItemStartIndices().get(pptxItem) > 0);
        assertTrue(built.getItemStartIndices().get(noteItem) > built.getItemStartIndices().get(pptxItem));
        assertEquals(LectureRenderModel.EntryKind.FILE_HEADER, built.getEntries().get(0).getEntryKind());
        assertEquals(LectureRenderModel.EntryKind.PDF_PAGE, built.getEntries().get(1).getEntryKind());
        assertEquals(LectureRenderModel.EntryKind.TEXT_DOC, built.getEntries().get(built.getEntries().size() - 1).getEntryKind());
    }

    private Path createSamplePdf(Path target, int pages) throws IOException {
        List<String> objs = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            if (i > 0) {
                kids.append(' ');
            }
            kids.append(3 + i).append(" 0 R");
        }
        int contentId = 3 + pages;
        int fontId = contentId + 1;
        objs.add("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
        objs.add("2 0 obj << /Type /Pages /Kids [" + kids + "] /Count " + pages + " >> endobj\n");
        for (int i = 0; i < pages; i++) {
            objs.add((3 + i) + " 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                    + "/Contents " + contentId + " 0 R /Resources << /Font << /F1 " + fontId + " 0 R >> >> >> endobj\n");
        }
        objs.add(contentId + " 0 obj << /Length 44 >> stream\nBT /F1 18 Tf 72 700 Td (Lecture Page) Tj ET\nendstream endobj\n");
        objs.add(fontId + " 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n");

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (String obj : objs) {
            offsets.add(pdf.length());
            pdf.append(obj);
        }
        int xrefPos = pdf.length();
        pdf.append("xref\n0 ").append(objs.size() + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer << /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xrefPos).append("\n%%EOF\n");
        Files.writeString(target, pdf.toString(), StandardCharsets.US_ASCII);
        return target;
    }

    private Path createSamplePptx(Path target, List<String> slideTexts) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            for (int i = 0; i < slideTexts.size(); i++) {
                String xml = "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
                        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                        + "<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>"
                        + slideTexts.get(i)
                        + "</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>";
                zos.putNextEntry(new ZipEntry("ppt/slides/slide" + (i + 1) + ".xml"));
                zos.write(xml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return target;
    }
}
