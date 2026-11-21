package com.calendarorganizer.course;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourseFilePreviewerTest {

    private final CourseFilePreviewer previewer = new CourseFilePreviewer();

    @AfterEach
    void tearDown() {
        previewer.cleanupAllPdfCaches();
    }

    @Test
    void detectsSupportedFileKinds() {
        assertEquals(CourseFilePreviewer.PreviewKind.PDF, previewer.detectKind(Path.of("appendix.PDF")));
        assertEquals(CourseFilePreviewer.PreviewKind.PPTX, previewer.detectKind(Path.of("slides.pptx")));
        assertEquals(CourseFilePreviewer.PreviewKind.IMAGE, previewer.detectKind(Path.of("diagram.png")));
        assertEquals(CourseFilePreviewer.PreviewKind.TEXT, previewer.detectKind(Path.of("syllabus.docx")));
        assertEquals(CourseFilePreviewer.PreviewKind.TEXT, previewer.detectKind(Path.of("code.java")));
        assertEquals(CourseFilePreviewer.PreviewKind.UNSUPPORTED, previewer.detectKind(Path.of("archive.zip")));
    }

    @Test
    void extractsPptxSlidesInNumericOrderWithNotesAndThumbnail(@TempDir Path tempDir) throws IOException {
        Path pptx = tempDir.resolve("lecture.pptx");
        byte[] fakeThumb = new byte[]{1, 2, 3, 4};

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(pptx), StandardCharsets.UTF_8)) {
            writeEntry(zos, "ppt/slides/slide10.xml", slideXml("Slide Ten Conclusion"));
            writeEntry(zos, "ppt/slides/slide2.xml", slideXml("Slide Two Topic"));
            writeEntry(zos, "ppt/slides/slide1.xml", slideXml("Slide One Intro"));
            writeEntry(zos, "ppt/slides/_rels/slide2.xml.rels",
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide\" Target=\"../notesSlides/notesSlide2.xml\"/>"
                            + "</Relationships>");
            writeEntry(zos, "ppt/notesSlides/notesSlide2.xml", slideXml("Remember exam question"));
            zos.putNextEntry(new ZipEntry("docProps/thumbnail.png"));
            zos.write(fakeThumb);
            zos.closeEntry();
        }

        CourseFilePreviewer.PptxPreview preview = previewer.extractPptx(pptx);
        assertEquals(3, preview.getSlides().size());
        assertTrue(preview.getSlides().get(0).contains("Slide One Intro"));
        assertTrue(preview.getSlides().get(1).contains("Slide Two Topic"));
        assertTrue(preview.getSlides().get(1).contains("Remember exam question"));
        assertTrue(preview.getSlides().get(2).contains("Slide Ten Conclusion"));
        assertArrayEquals(fakeThumb, preview.getThumbnailBytes());
    }

    @Test
    void extractsDocxTextAndTruncatesOversizedPlainText(@TempDir Path tempDir) throws IOException {
        Path docx = tempDir.resolve("notes.docx");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(docx), StandardCharsets.UTF_8)) {
            writeEntry(zos, "word/document.xml",
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                            + "<w:body><w:p><w:r><w:t>First paragraph</w:t></w:r></w:p>"
                            + "<w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p></w:body></w:document>");
        }

        String docxText = previewer.extractTextPreview(docx);
        assertTrue(docxText.contains("First paragraph"));
        assertTrue(docxText.contains("Second paragraph"));

        Path bigTxt = tempDir.resolve("huge.txt");
        Files.writeString(bigTxt, "A".repeat(300_000), StandardCharsets.UTF_8);
        String txtPreview = previewer.extractTextPreview(bigTxt);
        assertTrue(txtPreview.contains("[Preview truncated at 256 KB"));
    }

    @Test
    void inspectsAndRendersSingleAndMultiPagePdfsViaPdfBox(@TempDir Path tempDir) throws IOException {
        Path pdf = tempDir.resolve("sample.pdf");
        String minimalPdf = "%PDF-1.4\n"
                + "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
                + "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n"
                + "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj\n"
                + "4 0 obj << /Length 49 >> stream\n"
                + "BT /F1 24 Tf 72 700 Td (CMPT 201 Preview) Tj ET\n"
                + "endstream endobj\n"
                + "5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n"
                + "xref\n"
                + "0 6\n"
                + "0000000000 65535 f \n"
                + "0000000009 00000 n \n"
                + "0000000058 00000 n \n"
                + "0000000115 00000 n \n"
                + "0000000241 00000 n \n"
                + "0000000340 00000 n \n"
                + "trailer << /Size 6 /Root 1 0 R >>\n"
                + "startxref\n"
                + "410\n"
                + "%%EOF\n";
        Files.writeString(pdf, minimalPdf, StandardCharsets.US_ASCII);

        PdfRenderService.PdfMetadata singleMeta = previewer.inspectPdf(pdf);
        assertEquals(1, singleMeta.getPageCount());
        assertEquals(792.0 / 612.0, singleMeta.getAspectRatio(0), 0.02);

        BufferedImage page0 = previewer.renderPdfPageImage(pdf, 0, 800);
        assertNotNull(page0);
        assertTrue(page0.getWidth() >= 780 && page0.getWidth() <= 820);
        assertTrue(page0.getHeight() > 900);

        Path multiPdf = createMultiPageSamplePdf(tempDir.resolve("five-pages.pdf"), 5);
        PdfRenderService.PdfMetadata multiMeta = previewer.inspectPdf(multiPdf);
        assertEquals(5, multiMeta.getPageCount());

        BufferedImage page4 = previewer.renderPdfPageImage(multiPdf, 4, 600);
        assertNotNull(page4);
        assertTrue(page4.getWidth() >= 580 && page4.getWidth() <= 620);
    }

    private Path createMultiPageSamplePdf(Path target, int pageCount) throws IOException {
        List<String> objs = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            if (i > 0) {
                kids.append(' ');
            }
            kids.append(3 + i).append(" 0 R");
        }
        int contentId = 3 + pageCount;
        int fontId = contentId + 1;
        objs.add("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
        objs.add("2 0 obj << /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >> endobj\n");
        for (int i = 0; i < pageCount; i++) {
            objs.add((3 + i) + " 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                    + "/Contents " + contentId + " 0 R /Resources << /Font << /F1 " + fontId + " 0 R >> >> >> endobj\n");
        }
        objs.add(contentId + " 0 obj << /Length 49 >> stream\nBT /F1 24 Tf 72 700 Td (CMPT 201 Lecture) Tj ET\nendstream endobj\n");
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

    private String slideXml(String text) {
        return "<p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                + "<p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>" + text + "</a:t></a:r></a:p>"
                + "</p:txBody></p:sp></p:spTree></p:cSld></p:sld>";
    }

    private void writeEntry(ZipOutputStream zos, String name, String xml) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(xml.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
