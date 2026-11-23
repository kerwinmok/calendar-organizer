package com.calendarorganizer.course;

import javafx.scene.image.WritableImage;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class PdfPageCache {

    public static final long DEFAULT_MAX_BYTES = 16L * 1024L * 1024L;

    public static final class PageCacheKey {
        private final Path normalizedPath;
        private final long lastModifiedMillis;
        private final int pageIndex;
        private final int widthBucket;

        public PageCacheKey(Path normalizedPath, long lastModifiedMillis, int pageIndex, int widthBucket) {
            this.normalizedPath = normalizedPath.toAbsolutePath().normalize();
            this.lastModifiedMillis = lastModifiedMillis;
            this.pageIndex = pageIndex;
            this.widthBucket = widthBucket;
        }

        public Path getNormalizedPath() {
            return normalizedPath;
        }

        public long getLastModifiedMillis() {
            return lastModifiedMillis;
        }

        public int getPageIndex() {
            return pageIndex;
        }

        public int getWidthBucket() {
            return widthBucket;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PageCacheKey)) {
                return false;
            }
            PageCacheKey other = (PageCacheKey) obj;
            return lastModifiedMillis == other.lastModifiedMillis
                    && pageIndex == other.pageIndex
                    && widthBucket == other.widthBucket
                    && Objects.equals(normalizedPath, other.normalizedPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(normalizedPath, lastModifiedMillis, pageIndex, widthBucket);
        }
    }

    private static final class CacheEntry {
        final WritableImage image;
        final long byteEstimate;

        CacheEntry(WritableImage image, long byteEstimate) {
            this.image = image;
            this.byteEstimate = byteEstimate;
        }
    }

    private final long maxBytes;
    private final Map<PageCacheKey, CacheEntry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long currentBytes;

    public PdfPageCache() {
        this(DEFAULT_MAX_BYTES);
    }

    public PdfPageCache(long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public synchronized WritableImage get(PageCacheKey key) {
        if (key == null) {
            return null;
        }
        CacheEntry entry = entries.get(key);
        return entry != null ? entry.image : null;
    }

    public synchronized WritableImage getClosestForPage(Path filePath,
                                                      long lastModifiedMillis,
                                                      int pageIndex,
                                                      int preferredWidthBucket) {
        if (filePath == null) {
            return null;
        }
        Path normalized = filePath.toAbsolutePath().normalize();
        WritableImage bestImage = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestBucket = -1;

        for (Map.Entry<PageCacheKey, CacheEntry> entry : entries.entrySet()) {
            PageCacheKey key = entry.getKey();
            if (key.getPageIndex() != pageIndex
                    || key.getLastModifiedMillis() != lastModifiedMillis
                    || !normalized.equals(key.getNormalizedPath())) {
                continue;
            }
            int bucket = key.getWidthBucket();
            int distance = Math.abs(bucket - preferredWidthBucket);
            if (bestImage == null
                    || distance < bestDistance
                    || (distance == bestDistance && bucket > bestBucket)) {
                bestImage = entry.getValue().image;
                bestDistance = distance;
                bestBucket = bucket;
            }
        }
        return bestImage;
    }

    public synchronized void put(PageCacheKey key, WritableImage image, long byteEstimate) {
        if (key == null || image == null || byteEstimate <= 0L || byteEstimate > maxBytes) {
            return;
        }
        CacheEntry previous = entries.remove(key);
        if (previous != null) {
            currentBytes -= previous.byteEstimate;
        }
        while (currentBytes + byteEstimate > maxBytes && !entries.isEmpty()) {
            Iterator<Map.Entry<PageCacheKey, CacheEntry>> it = entries.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            Map.Entry<PageCacheKey, CacheEntry> eldest = it.next();
            currentBytes -= eldest.getValue().byteEstimate;
            it.remove();
        }
        entries.put(key, new CacheEntry(image, byteEstimate));
        currentBytes += byteEstimate;
    }

    public synchronized void evictFile(Path filePath) {
        if (filePath == null) {
            return;
        }
        Path normalized = filePath.toAbsolutePath().normalize();
        Iterator<Map.Entry<PageCacheKey, CacheEntry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PageCacheKey, CacheEntry> entry = it.next();
            if (normalized.equals(entry.getKey().getNormalizedPath())) {
                currentBytes -= entry.getValue().byteEstimate;
                it.remove();
            }
        }
    }

    public synchronized void clear() {
        entries.clear();
        currentBytes = 0L;
    }

    public synchronized long currentBytes() {
        return currentBytes;
    }

    public synchronized int size() {
        return entries.size();
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
