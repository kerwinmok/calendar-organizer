package com.calendarorganizer.course;

import javafx.scene.image.WritableImage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CourseFilePreviewer {

    public enum PreviewKind {
        PDF,
        PPTX,
        TEXT,
        IMAGE,
        UNSUPPORTED
    }

    public static class PptxPreview {
        private final List<String> slides;
        private final byte[] thumbnailBytes;

        public PptxPreview(List<String> slides, byte[] thumbnailBytes) {
            this.slides = slides;
            this.thumbnailBytes = thumbnailBytes;
        }

        public List<String> getSlides() {
            return slides;
        }

        public byte[] getThumbnailBytes() {
            return thumbnailBytes;
        }
    }

    private static final int MAX_TEXT_BYTES = 262_144;
    private static final int MAX_TEXT_CHARS = 200_000;
    private static final int MAX_THUMBNAIL_BYTES = 524_288;
    private static final Pattern SLIDE_ENTRY_PATTERN = Pattern.compile("^ppt/slides/slide(\\d+)\\.xml$");

    private final PdfRenderService pdfRenderService;

    public CourseFilePreviewer() {
        this(new PdfRenderService());
    }

    public CourseFilePreviewer(PdfRenderService pdfRenderService) {
        this.pdfRenderService = pdfRenderService;
    }

    public PreviewKind detectKind(Path file) {
        if (file == null) {
            return PreviewKind.UNSUPPORTED;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return PreviewKind.UNSUPPORTED;
        }
        String ext = name.substring(dot + 1);
        switch (ext) {
            case "pdf":
                return PreviewKind.PDF;
            case "pptx":
                return PreviewKind.PPTX;
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "bmp":
                return PreviewKind.IMAGE;
            case "docx":
            case "txt":
            case "csv":
            case "json":
            case "xml":
            case "java":
            case "py":
            case "c":
            case "cpp":
            case "h":
            case "js":
            case "ts":
            case "html":
            case "css":
            case "sql":
            case "log":
            case "yaml":
            case "yml":
                return PreviewKind.TEXT;
            default:
                return PreviewKind.UNSUPPORTED;
        }
    }

    public PptxPreview extractPptx(Path pptxPath) throws IOException {
        List<SlideEntry> slideEntries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(pptxPath.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Matcher matcher = SLIDE_ENTRY_PATTERN.matcher(entry.getName());
                if (matcher.matches()) {
                    slideEntries.add(new SlideEntry(Integer.parseInt(matcher.group(1)), entry));
                }
            }
            slideEntries.sort(Comparator.comparingInt(s -> s.number));

            List<String> slideTexts = new ArrayList<>();
            int total = slideEntries.size();
            for (int i = 0; i < total; i++) {
                SlideEntry slideEntry = slideEntries.get(i);
                String body = readXmlParagraphs(zip, slideEntry.entry);
                if (body.isBlank()) {
                    body = "[No text on this slide]";
                }
                StringBuilder slideText = new StringBuilder();
                slideText.append("Slide ").append(i + 1).append(" of ").append(total).append("\n\n");
                slideText.append(body);

                String notesPath = findNotesPathForSlide(zip, slideEntry.number);
                if (notesPath != null) {
                    ZipEntry notesEntry = zip.getEntry(notesPath);
                    if (notesEntry != null) {
                        String notes = readXmlParagraphs(zip, notesEntry);
                        if (!notes.isBlank()) {
                            slideText.append("\n\n--- Notes ---\n").append(notes);
                        }
                    }
                }
                slideTexts.add(slideText.toString());
            }

            if (slideTexts.isEmpty()) {
                slideTexts.add("Slide 1 of 1\n\n[No readable slides found in presentation]");
            }
            byte[] thumbnail = readOptionalThumbnail(zip);
            return new PptxPreview(slideTexts, thumbnail);
        }
    }

    public String extractTextPreview(Path filePath) throws IOException {
        String name = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return extractDocxText(filePath);
        }
        return readBoundedPlainText(filePath);
    }

    public PdfRenderService.PdfMetadata inspectPdf(Path pdfPath) throws IOException {
        return pdfRenderService.inspectPdf(pdfPath);
    }

    public BufferedImage renderPdfPageImage(Path pdfPath, int pageIndex, int targetWidthPx) throws IOException {
        return pdfRenderService.renderPageImage(pdfPath, pageIndex, targetWidthPx);
    }

    public WritableImage renderPdfPageFxImage(Path pdfPath, int pageIndex, int targetWidthPx) throws IOException {
        return pdfRenderService.renderPageFxImage(pdfPath, pageIndex, targetWidthPx);
    }

    public WritableImage getCachedPdfPageFxImage(Path pdfPath, int pageIndex, int targetWidthPx) {
        return pdfRenderService.getCachedFxImage(pdfPath, pageIndex, targetWidthPx);
    }

    public WritableImage getCachedPdfPageFxImage(Path pdfPath,
                                                 long lastModifiedMillis,
                                                 int pageIndex,
                                                 int targetWidthPx) {
        return pdfRenderService.getCachedFxImage(pdfPath, lastModifiedMillis, pageIndex, targetWidthPx);
    }

    public WritableImage getClosestCachedPdfPageFxImage(Path pdfPath,
                                                        long lastModifiedMillis,
                                                        int pageIndex,
                                                        int targetWidthPx) {
        return pdfRenderService.getClosestCachedFxImage(pdfPath, lastModifiedMillis, pageIndex, targetWidthPx);
    }

    public PdfRenderService getPdfRenderService() {
        return pdfRenderService;
    }

    public void clearPdfMemory() {
        pdfRenderService.clearCache();
        pdfRenderService.closeAllDocuments();
    }

    public void cleanupAllPdfCaches() {
        pdfRenderService.close();
    }

    private String extractDocxText(Path docxPath) throws IOException {
        try (ZipFile zip = new ZipFile(docxPath.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                return "[No readable Word document content found]";
            }
            String text = readXmlParagraphs(zip, entry);
            if (text.length() > MAX_TEXT_CHARS) {
                return text.substring(0, MAX_TEXT_CHARS)
                        + "\n\n[Preview truncated — click Open File to view full document]";
            }
            if (text.isBlank()) {
                return "[Document contains no readable text]";
            }
            return text;
        }
    }

    private String readBoundedPlainText(Path filePath) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean truncated = false;
        try (InputStream is = Files.newInputStream(filePath)) {
            byte[] buf = new byte[8192];
            int remaining = MAX_TEXT_BYTES;
            while (remaining > 0) {
                int toRead = Math.min(buf.length, remaining);
                int read = is.read(buf, 0, toRead);
                if (read < 0) {
                    break;
                }
                out.write(buf, 0, read);
                remaining = remaining - read;
            }
            if (remaining == 0 && is.read() != -1) {
                truncated = true;
            }
        }
        String text = out.toString(StandardCharsets.UTF_8);
        if (truncated) {
            return text + "\n\n[Preview truncated at 256 KB — click Open File to view full file]";
        }
        return text;
    }

    private String findNotesPathForSlide(ZipFile zip, int slideNumber) {
        ZipEntry rels = zip.getEntry("ppt/slides/_rels/slide" + slideNumber + ".xml.rels");
        if (rels == null) {
            return null;
        }
        try (InputStream is = zip.getInputStream(rels)) {
            Document doc = parseXmlSafely(is);
            NodeList nodes = doc.getElementsByTagName("Relationship");
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node instanceof Element) {
                    Element el = (Element) node;
                    String type = el.getAttribute("Type");
                    String target = el.getAttribute("Target");
                    if (type.endsWith("/notesSlide") && !target.isBlank()) {
                        String clean = target.replace('\\', '/');
                        if (clean.startsWith("../")) {
                            return "ppt/" + clean.substring(3);
                        }
                        if (clean.startsWith("ppt/")) {
                            return clean;
                        }
                        return "ppt/slides/" + clean;
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private byte[] readOptionalThumbnail(ZipFile zip) {
        ZipEntry entry = zip.getEntry("docProps/thumbnail.jpeg");
        if (entry == null) {
            entry = zip.getEntry("docProps/thumbnail.png");
        }
        if (entry == null || entry.getSize() > MAX_THUMBNAIL_BYTES) {
            return null;
        }
        try (InputStream is = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int read;
            while ((read = is.read(buf)) >= 0) {
                total = total + read;
                if (total > MAX_THUMBNAIL_BYTES) {
                    return null;
                }
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }

    private String readXmlParagraphs(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream is = zip.getInputStream(entry)) {
            Document doc = parseXmlSafely(is);
            StringBuilder out = new StringBuilder();
            collectParagraphs(doc.getDocumentElement(), out);
            return out.toString().trim();
        } catch (Exception ex) {
            throw new IOException("Could not parse XML content: " + ex.getMessage(), ex);
        }
    }

    private void collectParagraphs(Node node, StringBuilder out) {
        if (node == null || out.length() > MAX_TEXT_CHARS + 1024) {
            return;
        }
        if (node.getNodeType() == Node.ELEMENT_NODE && "p".equals(node.getLocalName())) {
            StringBuilder line = new StringBuilder();
            collectTextRuns(node, line);
            String clean = line.toString().trim();
            if (!clean.isEmpty()) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(clean);
            }
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectParagraphs(children.item(i), out);
        }
    }

    private void collectTextRuns(Node node, StringBuilder line) {
        if (node == null) {
            return;
        }
        if (node.getNodeType() == Node.ELEMENT_NODE && "t".equals(node.getLocalName())) {
            line.append(node.getTextContent());
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectTextRuns(children.item(i), line);
        }
    }

    //blocks external dtd and entity resolution when reading zipped office xml
    private Document parseXmlSafely(InputStream stream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(stream);
    }

    private static class SlideEntry {
        final int number;
        final ZipEntry entry;

        SlideEntry(int number, ZipEntry entry) {
            this.number = number;
            this.entry = entry;
        }
    }
}
