package com.calendarorganizer.course;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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

    public static class PdfPageRender {
        private final int pageIndex;
        private final int totalPages;
        private final Path pngPath;

        public PdfPageRender(int pageIndex, int totalPages, Path pngPath) {
            this.pageIndex = pageIndex;
            this.totalPages = totalPages;
            this.pngPath = pngPath;
        }

        public int getPageIndex() {
            return pageIndex;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public Path getPngPath() {
            return pngPath;
        }
    }

    private static final int MAX_TEXT_BYTES = 262_144;
    private static final int MAX_TEXT_CHARS = 200_000;
    private static final int MAX_THUMBNAIL_BYTES = 524_288;
    private static final String PDF_CACHE_ROOT_NAME = "calendar-organizer-pdf-cache";
    private static final Pattern SLIDE_ENTRY_PATTERN = Pattern.compile("^ppt/slides/slide(\\d+)\\.xml$");

    private final AtomicReference<Process> activePdfProcess = new AtomicReference<>();

    private static final String PDF_RENDER_SCRIPT = String.join("; ",
            "$ErrorActionPreference = 'Stop'",
            "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
            "$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
            "$asTaskAction = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' })[0]",
            "function Await-Op($op, $rt) { $t = $asTaskGeneric.MakeGenericMethod($rt).Invoke($null, @($op)); $t.Wait(); return $t.Result }",
            "function Await-Act($act) { $t = $asTaskAction.Invoke($null, @($act)); $t.Wait() }",
            "[Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Data.Pdf.PdfDocument, Windows.Data.Pdf, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime] | Out-Null",
            "$file = Await-Op ([Windows.Storage.StorageFile]::GetFileFromPathAsync($env:PDF_SRC)) ([Windows.Storage.StorageFile])",
            "$doc = Await-Op ([Windows.Data.Pdf.PdfDocument]::LoadFromFileAsync($file)) ([Windows.Data.Pdf.PdfDocument])",
            "$count = [int]$doc.PageCount",
            "if ($count -le 0) { throw 'PDF has no pages.' }",
            "$idx = [Math]::Max(0, [Math]::Min($count - 1, [int]$env:PDF_PAGE))",
            "$page = $doc.GetPage([uint32]$idx)",
            "try { $stream = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream; try { $opts = New-Object Windows.Data.Pdf.PdfPageRenderOptions; $opts.DestinationWidth = [uint32][int]$env:PDF_WIDTH; Await-Act ($page.RenderToStreamAsync($stream, $opts)); $inStream = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream.GetInputStreamAt(0)); try { $outFs = [System.IO.File]::Create($env:PDF_OUT); try { $inStream.CopyTo($outFs) } finally { $outFs.Dispose() } } finally { $inStream.Dispose() } } finally { $stream.Dispose() } } finally { $page.Dispose() }",
            "Write-Output ('PAGES=' + $count + ';INDEX=' + $idx)"
    );

    private static final String PDF_RENDER_ALL_SCRIPT = String.join("; ",
            "$ErrorActionPreference = 'Stop'",
            "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
            "$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
            "$asTaskAction = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' })[0]",
            "function Await-Op($op, $rt) { $t = $asTaskGeneric.MakeGenericMethod($rt).Invoke($null, @($op)); $t.Wait(); return $t.Result }",
            "function Await-Act($act) { $t = $asTaskAction.Invoke($null, @($act)); $t.Wait() }",
            "[Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Data.Pdf.PdfDocument, Windows.Data.Pdf, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime] | Out-Null",
            "$file = Await-Op ([Windows.Storage.StorageFile]::GetFileFromPathAsync($env:PDF_SRC)) ([Windows.Storage.StorageFile])",
            "$doc = Await-Op ([Windows.Data.Pdf.PdfDocument]::LoadFromFileAsync($file)) ([Windows.Data.Pdf.PdfDocument])",
            "$count = [int]$doc.PageCount",
            "if ($count -le 0) { throw 'PDF has no pages.' }",
            "[Console]::WriteLine('TOTAL=' + $count)",
            "[Console]::Out.Flush()",
            "for ($i = 0; $i -lt $count; $i++) { $page = $doc.GetPage([uint32]$i); try { $stream = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream; try { $opts = New-Object Windows.Data.Pdf.PdfPageRenderOptions; $opts.DestinationWidth = [uint32][int]$env:PDF_WIDTH; Await-Act ($page.RenderToStreamAsync($stream, $opts)); $inStream = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream.GetInputStreamAt(0)); try { $outFile = Join-Path $env:PDF_OUT_DIR ('page-' + $i.ToString('D4') + '.png'); $outFs = [System.IO.File]::Create($outFile); try { $inStream.CopyTo($outFs) } finally { $outFs.Dispose() } } finally { $inStream.Dispose() } } finally { $stream.Dispose() } } finally { $page.Dispose() }; [Console]::WriteLine('PAGE=' + $i); [Console]::Out.Flush() }"
    );

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

    public PdfPageRender renderPdfPage(Path pdfPath, int pageIndex, int targetWidthPx) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            throw new IOException("Built-in PDF preview is supported on Windows only.");
        }

        int safeWidth = Math.max(400, Math.min(1400, targetWidthPx));
        int requestedPage = Math.max(0, pageIndex);
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        Path outPng = tempDir.resolve("calendar-organizer-pdf-preview.png");
        outPng.toFile().deleteOnExit();
        Files.deleteIfExists(outPng);

        Path outLog = Files.createTempFile(tempDir, "calendar-organizer-pdf-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    PDF_RENDER_SCRIPT
            );
            pb.environment().put("PDF_SRC", pdfPath.toAbsolutePath().toString());
            pb.environment().put("PDF_PAGE", Integer.toString(requestedPage));
            pb.environment().put("PDF_OUT", outPng.toAbsolutePath().toString());
            pb.environment().put("PDF_WIDTH", Integer.toString(safeWidth));
            pb.redirectErrorStream(true);
            pb.redirectOutput(outLog.toFile());

            Process proc = pb.start();
            registerActiveProcess(proc);
            boolean finished = proc.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("Timed out while rendering PDF preview.");
            }

            String logText = Files.readString(outLog, StandardCharsets.UTF_8).trim();
            if (proc.exitValue() != 0 || !Files.isRegularFile(outPng) || Files.size(outPng) == 0L) {
                throw new IOException(firstNonBlankLine(logText, "Could not render PDF preview."));
            }
            return parsePdfRenderResult(logText, outPng, requestedPage);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("PDF preview interrupted.", ex);
        } finally {
            clearActiveProcess();
            Files.deleteIfExists(outLog);
        }
    }

    public void renderAllPdfPages(Path pdfPath,
                                  int targetWidthPx,
                                  Path outputDir,
                                  AtomicBoolean cancelled,
                                  IntConsumer onTotalPages,
                                  BiConsumer<Integer, Path> onPageRendered) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            throw new IOException("Built-in PDF preview is supported on Windows only.");
        }
        int safeWidth = Math.max(400, Math.min(1200, targetWidthPx));
        Files.createDirectories(outputDir);

        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                PDF_RENDER_ALL_SCRIPT
        );
        pb.environment().put("PDF_SRC", pdfPath.toAbsolutePath().toString());
        pb.environment().put("PDF_OUT_DIR", outputDir.toAbsolutePath().toString());
        pb.environment().put("PDF_WIDTH", Integer.toString(safeWidth));
        pb.redirectErrorStream(true);

        Process proc = pb.start();
        registerActiveProcess(proc);
        String firstError = null;
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled != null && cancelled.get()) {
                        proc.destroyForcibly();
                        break;
                    }
                    String trimmed = line.trim();
                    if (trimmed.startsWith("TOTAL=") && onTotalPages != null) {
                        int total = Math.max(1, Integer.parseInt(trimmed.substring(6)));
                        onTotalPages.accept(total);
                    } else if (trimmed.startsWith("PAGE=") && onPageRendered != null) {
                        int idx = Integer.parseInt(trimmed.substring(5));
                        Path pageFile = outputDir.resolve(String.format("page-%04d.png", idx));
                        if (Files.isRegularFile(pageFile)) {
                            onPageRendered.accept(idx, pageFile);
                        }
                    } else if (!trimmed.isEmpty() && firstError == null) {
                        firstError = trimmed;
                    }
                }
            }

            if (cancelled != null && cancelled.get()) {
                return;
            }
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.isAlive()) {
                proc.destroyForcibly();
                throw new IOException("PDF rendering did not finish cleanly.");
            }
            if (proc.exitValue() != 0) {
                if (firstError == null || firstError.isBlank()) {
                    firstError = "Could not render PDF pages.";
                }
                throw new IOException(firstError);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (cancelled == null || !cancelled.get()) {
                throw new IOException("PDF preview interrupted.", ex);
            }
        } finally {
            activePdfProcess.compareAndSet(proc, null);
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    public void cancelActivePdfRender() {
        Process proc = activePdfProcess.getAndSet(null);
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
        }
    }

    public Path createPdfCacheSessionDir(long sessionId) throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), PDF_CACHE_ROOT_NAME);
        Path sessionDir = root.resolve("session-" + sessionId);
        deleteDirectoryRecursively(sessionDir);
        Files.createDirectories(sessionDir);
        return sessionDir;
    }

    public void cleanupAllPdfCaches() {
        cancelActivePdfRender();
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        deleteQuietly(tempDir.resolve("calendar-organizer-pdf-preview.png"));
        deleteDirectoryRecursively(tempDir.resolve(PDF_CACHE_ROOT_NAME));
    }

    private void registerActiveProcess(Process proc) {
        Process previous = activePdfProcess.getAndSet(proc);
        if (previous != null && previous.isAlive()) {
            previous.destroyForcibly();
        }
    }

    private void clearActiveProcess() {
        Process proc = activePdfProcess.getAndSet(null);
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
        }
    }

    private void deleteDirectoryRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                deleteQuietly(path);
            }
        } catch (IOException ignored) {
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private PdfPageRender parsePdfRenderResult(String logText, Path outPng, int fallbackIndex) throws IOException {
        int pages = 1;
        int actualIndex = fallbackIndex;
        for (String line : logText.split("\\R")) {
            if (line.startsWith("PAGES=")) {
                String[] parts = line.trim().split(";");
                for (String part : parts) {
                    if (part.startsWith("PAGES=")) {
                        pages = Math.max(1, Integer.parseInt(part.substring(6)));
                    } else if (part.startsWith("INDEX=")) {
                        actualIndex = Math.max(0, Integer.parseInt(part.substring(6)));
                    }
                }
                return new PdfPageRender(actualIndex, pages, outPng);
            }
        }
        throw new IOException("PDF renderer returned unexpected output.");
    }

    private String firstNonBlankLine(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String[] lines = raw.split("\\R");
        for (String line : lines) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return fallback;
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
