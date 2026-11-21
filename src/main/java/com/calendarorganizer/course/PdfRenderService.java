package com.calendarorganizer.course;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PdfRenderService implements AutoCloseable {

    public static final class PdfMetadata {
        private final int pageCount;
        private final double[] aspectRatios;

        public PdfMetadata(int pageCount, double[] aspectRatios) {
            this.pageCount = pageCount;
            this.aspectRatios = aspectRatios;
        }

        public int getPageCount() {
            return pageCount;
        }

        public double getAspectRatio(int pageIndex) {
            if (aspectRatios == null || aspectRatios.length == 0) {
                return 792.0 / 612.0;
            }
            int safe = Math.max(0, Math.min(aspectRatios.length - 1, pageIndex));
            return aspectRatios[safe];
        }
    }

    private static final class PdfDocHandle {
        final long lastModifiedMillis;
        final PDDocument document;
        final PDFRenderer renderer;
        final Object renderLock = new Object();
        boolean closed;

        PdfDocHandle(long lastModifiedMillis, PDDocument document) {
            this.lastModifiedMillis = lastModifiedMillis;
            this.document = document;
            this.renderer = new PDFRenderer(document);
            this.renderer.setSubsamplingAllowed(true);
        }

        void closeQuietly() {
            synchronized (renderLock) {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    document.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final int MAX_OPEN_DOCS = 2;
    private static final int MIN_WIDTH_PX = 240;
    private static final int MAX_WIDTH_PX = 2200;
    private static final long MAX_RENDER_PIXELS = 4_500_000L;
    private static final long PDFBOX_MAIN_MEM_BYTES = 4L * 1024L * 1024L;

    private final PdfPageCache pageCache;
    private final Object docsLock = new Object();
    private final Map<Path, PdfDocHandle> openDocs = new LinkedHashMap<>(4, 0.75f, true);

    public PdfRenderService() {
        this(new PdfPageCache());
    }

    public PdfRenderService(PdfPageCache pageCache) {
        this.pageCache = pageCache;
    }

    public PdfMetadata inspectPdf(Path pdfPath) throws IOException {
        Path normalized = validatePdfPath(pdfPath);
        long mtime = readModifiedMillis(normalized);

        PdfDocHandle cached = peekOpenHandle(normalized, mtime);
        if (cached != null) {
            synchronized (cached.renderLock) {
                if (!cached.closed) {
                    return extractMetadata(cached.document);
                }
            }
        }

        try (PDDocument doc = loadDocument(normalized)) {
            return extractMetadata(doc);
        }
    }

    public BufferedImage renderPageImage(Path pdfPath, int pageIndex, int targetWidthPx) throws IOException {
        Path normalized = validatePdfPath(pdfPath);
        int clampedWidth = clampWidth(targetWidthPx);
        long mtime = readModifiedMillis(normalized);

        for (int attempt = 0; attempt < 2; attempt++) {
            PdfDocHandle handle = acquireDocHandle(normalized, mtime);
            synchronized (handle.renderLock) {
                if (handle.closed) {
                    continue;
                }
                return renderWithHandle(handle, pageIndex, clampedWidth);
            }
        }
        throw new IOException("Could not open PDF document for rendering.");
    }

    public WritableImage getCachedFxImage(Path pdfPath, int pageIndex, int targetWidthPx) {
        if (pdfPath == null) {
            return null;
        }
        Path normalized = pdfPath.toAbsolutePath().normalize();
        long mtime = readModifiedMillis(normalized);
        int bucket = toWidthBucket(targetWidthPx);
        return pageCache.get(new PdfPageCache.PageCacheKey(normalized, mtime, Math.max(0, pageIndex), bucket));
    }

    public WritableImage renderPageFxImage(Path pdfPath, int pageIndex, int targetWidthPx) throws IOException {
        Path normalized = validatePdfPath(pdfPath);
        long mtime = readModifiedMillis(normalized);
        int safePage = Math.max(0, pageIndex);
        int bucket = toWidthBucket(targetWidthPx);
        PdfPageCache.PageCacheKey key = new PdfPageCache.PageCacheKey(normalized, mtime, safePage, bucket);

        WritableImage cached = pageCache.get(key);
        if (cached != null) {
            return cached;
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            PdfDocHandle handle = acquireDocHandle(normalized, mtime);
            synchronized (handle.renderLock) {
                if (handle.closed) {
                    continue;
                }
                WritableImage rechecked = pageCache.get(key);
                if (rechecked != null) {
                    return rechecked;
                }
                BufferedImage buffered = renderWithHandle(handle, safePage, bucket);
                WritableImage fxImage = toWritableImage(buffered);
                long estimateBytes = (long) buffered.getWidth() * buffered.getHeight() * 4L;
                pageCache.put(key, fxImage, estimateBytes);
                return fxImage;
            }
        }
        throw new IOException("Could not open PDF document for rendering.");
    }

    public void evictFile(Path pdfPath) {
        if (pdfPath == null) {
            return;
        }
        Path normalized = pdfPath.toAbsolutePath().normalize();
        pageCache.evictFile(normalized);
        PdfDocHandle removed;
        synchronized (docsLock) {
            removed = openDocs.remove(normalized);
        }
        if (removed != null) {
            removed.closeQuietly();
        }
    }

    public void clearCache() {
        pageCache.clear();
    }

    public void closeAllDocuments() {
        List<PdfDocHandle> toClose;
        synchronized (docsLock) {
            toClose = new ArrayList<>(openDocs.values());
            openDocs.clear();
        }
        for (PdfDocHandle handle : toClose) {
            handle.closeQuietly();
        }
    }

    @Override
    public void close() {
        closeAllDocuments();
        clearCache();
    }

    public PdfPageCache getPageCache() {
        return pageCache;
    }

    private BufferedImage renderWithHandle(PdfDocHandle handle, int pageIndex, int targetWidthPx) throws IOException {
        int count = handle.document.getNumberOfPages();
        if (count <= 0) {
            throw new IOException("PDF has no pages.");
        }
        int safePage = Math.max(0, Math.min(count - 1, pageIndex));
        PDPage page = handle.document.getPage(safePage);
        PageSize size = readPageSize(page);
        float scale = (float) (targetWidthPx / size.widthPt);
        if (scale <= 0f || Float.isNaN(scale) || Float.isInfinite(scale)) {
            scale = 1.0f;
        }
        double estWidth = size.widthPt * scale;
        double estHeight = size.heightPt * scale;
        double estPixels = estWidth * estHeight;
        if (estPixels > MAX_RENDER_PIXELS && estPixels > 0.0) {
            scale = (float) (scale * Math.sqrt(MAX_RENDER_PIXELS / estPixels));
        }
        return handle.renderer.renderImage(safePage, scale, ImageType.RGB);
    }

    private PdfMetadata extractMetadata(PDDocument doc) throws IOException {
        int count = doc.getNumberOfPages();
        if (count <= 0) {
            throw new IOException("PDF has no pages.");
        }
        double[] ratios = new double[count];
        for (int i = 0; i < count; i++) {
            PageSize size = readPageSize(doc.getPage(i));
            double ratio = size.heightPt / size.widthPt;
            ratios[i] = Math.max(0.35, Math.min(3.0, ratio));
        }
        return new PdfMetadata(count, ratios);
    }

    private PageSize readPageSize(PDPage page) {
        PDRectangle box = page.getCropBox();
        if (box == null) {
            box = page.getMediaBox();
        }
        double w = box != null ? box.getWidth() : 612.0;
        double h = box != null ? box.getHeight() : 792.0;
        if (w <= 0.0 || h <= 0.0) {
            w = 612.0;
            h = 792.0;
        }
        int rot = ((page.getRotation() % 360) + 360) % 360;
        if (rot == 90 || rot == 270) {
            return new PageSize(h, w);
        }
        return new PageSize(w, h);
    }

    private WritableImage toWritableImage(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage rgbImage = source;
        if (source.getType() != BufferedImage.TYPE_INT_RGB
                || !(source.getRaster().getDataBuffer() instanceof DataBufferInt)) {
            rgbImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgbImage.createGraphics();
            try {
                g.drawImage(source, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        int[] pixels = ((DataBufferInt) rgbImage.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] |= 0xFF000000;
        }
        WritableImage fxImage = new WritableImage(width, height);
        fxImage.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbPreInstance(), pixels, 0, width);
        return fxImage;
    }

    private PdfDocHandle peekOpenHandle(Path normalized, long mtime) {
        synchronized (docsLock) {
            PdfDocHandle existing = openDocs.get(normalized);
            if (existing != null && existing.lastModifiedMillis == mtime && !existing.closed) {
                return existing;
            }
            return null;
        }
    }

    private PdfDocHandle acquireDocHandle(Path normalized, long mtime) throws IOException {
        PdfDocHandle stale = null;
        synchronized (docsLock) {
            PdfDocHandle existing = openDocs.get(normalized);
            if (existing != null) {
                if (existing.lastModifiedMillis == mtime && !existing.closed) {
                    return existing;
                }
                openDocs.remove(normalized);
                stale = existing;
            }
        }
        if (stale != null) {
            stale.closeQuietly();
            pageCache.evictFile(normalized);
        }

        PDDocument loaded = loadDocument(normalized);
        PdfDocHandle created = new PdfDocHandle(mtime, loaded);
        List<PdfDocHandle> evicted = new ArrayList<>();
        synchronized (docsLock) {
            PdfDocHandle racing = openDocs.get(normalized);
            if (racing != null && racing.lastModifiedMillis == mtime && !racing.closed) {
                evicted.add(created);
                return racing;
            }
            if (racing != null) {
                openDocs.remove(normalized);
                evicted.add(racing);
            }
            openDocs.put(normalized, created);
            while (openDocs.size() > MAX_OPEN_DOCS) {
                Iterator<Map.Entry<Path, PdfDocHandle>> it = openDocs.entrySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                Map.Entry<Path, PdfDocHandle> eldest = it.next();
                if (eldest.getValue() == created) {
                    break;
                }
                evicted.add(eldest.getValue());
                it.remove();
            }
        }
        for (PdfDocHandle handle : evicted) {
            handle.closeQuietly();
        }
        return created;
    }

    private PDDocument loadDocument(Path normalized) throws IOException {
        return Loader.loadPDF(
                normalized.toFile(),
                MemoryUsageSetting.setupMixed(PDFBOX_MAIN_MEM_BYTES).streamCache
        );
    }

    private Path validatePdfPath(Path pdfPath) throws IOException {
        if (pdfPath == null) {
            throw new IOException("PDF path cannot be null.");
        }
        Path normalized = pdfPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("PDF file does not exist: " + normalized.getFileName());
        }
        return normalized;
    }

    private long readModifiedMillis(Path normalized) {
        try {
            return Files.getLastModifiedTime(normalized).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private int clampWidth(int widthPx) {
        return Math.max(MIN_WIDTH_PX, Math.min(MAX_WIDTH_PX, widthPx));
    }

    public static int toWidthBucket(int targetWidthPx) {
        int clamped = Math.max(MIN_WIDTH_PX, Math.min(MAX_WIDTH_PX, targetWidthPx));
        int bucket = ((clamped + 31) / 64) * 64;
        return Math.max(MIN_WIDTH_PX, Math.min(MAX_WIDTH_PX, bucket));
    }

    private static final class PageSize {
        final double widthPt;
        final double heightPt;

        PageSize(double widthPt, double heightPt) {
            this.widthPt = widthPt;
            this.heightPt = heightPt;
        }
    }
}
