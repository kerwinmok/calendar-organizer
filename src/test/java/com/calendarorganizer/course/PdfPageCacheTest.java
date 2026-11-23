package com.calendarorganizer.course;

import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PdfPageCacheTest {

    @Test
    void enforcesByteBudgetAndEvictsLeastRecentlyUsedEntries() {
        PdfPageCache cache = new PdfPageCache(1_000L);
        Path docA = Path.of("a.pdf");
        Path docB = Path.of("b.pdf");

        PdfPageCache.PageCacheKey k1 = new PdfPageCache.PageCacheKey(docA, 100L, 0, 640);
        PdfPageCache.PageCacheKey k2 = new PdfPageCache.PageCacheKey(docA, 100L, 1, 640);
        PdfPageCache.PageCacheKey k3 = new PdfPageCache.PageCacheKey(docB, 200L, 0, 640);

        cache.put(k1, new WritableImage(10, 10), 400L);
        cache.put(k2, new WritableImage(10, 10), 400L);
        assertEquals(2, cache.size());
        assertEquals(800L, cache.currentBytes());

        assertNotNull(cache.get(k1));

        cache.put(k3, new WritableImage(10, 10), 400L);
        assertEquals(2, cache.size());
        assertEquals(800L, cache.currentBytes());
        assertNotNull(cache.get(k1));
        assertNull(cache.get(k2));
        assertNotNull(cache.get(k3));
    }

    @Test
    void evictsFileAndClearsAllEntries() {
        PdfPageCache cache = new PdfPageCache(2_000L);
        Path docA = Path.of("course", "a.pdf");
        Path docB = Path.of("course", "b.pdf");

        PdfPageCache.PageCacheKey k1 = new PdfPageCache.PageCacheKey(docA, 10L, 0, 640);
        PdfPageCache.PageCacheKey k2 = new PdfPageCache.PageCacheKey(docA, 10L, 1, 640);
        PdfPageCache.PageCacheKey k3 = new PdfPageCache.PageCacheKey(docB, 10L, 0, 640);

        cache.put(k1, new WritableImage(10, 10), 500L);
        cache.put(k2, new WritableImage(10, 10), 500L);
        cache.put(k3, new WritableImage(10, 10), 500L);

        cache.evictFile(docA);
        assertEquals(1, cache.size());
        assertEquals(500L, cache.currentBytes());
        assertNotNull(cache.get(k3));

        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0L, cache.currentBytes());
    }

    @Test
    void returnsClosestCachedWidthAndTieBreaksToLargerBucket() {
        PdfPageCache cache = new PdfPageCache(4_000L);
        Path docA = Path.of("slides.pdf");
        WritableImage img384 = new WritableImage(10, 10);
        WritableImage img640 = new WritableImage(12, 12);

        cache.put(new PdfPageCache.PageCacheKey(docA, 500L, 2, 384), img384, 500L);
        cache.put(new PdfPageCache.PageCacheKey(docA, 500L, 2, 640), img640, 500L);

        assertSame(img384, cache.getClosestForPage(docA, 500L, 2, 384));
        assertSame(img384, cache.getClosestForPage(docA, 500L, 2, 420));
        assertSame(img640, cache.getClosestForPage(docA, 500L, 2, 512));
        assertSame(img640, cache.getClosestForPage(docA, 500L, 2, 896));
        assertNull(cache.getClosestForPage(docA, 501L, 2, 512));
        assertNull(cache.getClosestForPage(docA, 500L, 3, 512));
        assertNull(cache.getClosestForPage(null, 500L, 2, 512));
    }
}
