package com.calendarorganizer.notebook;

import com.calendarorganizer.course.Course;
import com.calendarorganizer.course.CourseItem;
import com.calendarorganizer.course.CourseManager;
import com.calendarorganizer.course.CourseSection;
import com.calendarorganizer.model.CalendarEvent;
import com.calendarorganizer.model.EventCategory;
import com.calendarorganizer.model.EventSource;
import com.calendarorganizer.service.CalendarService;
import com.calendarorganizer.storage.CalendarRepository;
import com.calendarorganizer.storage.DatabaseManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiNotebookSyncServiceTest {

    @Test
    void syncsCourseFilesDeduplicatesByHashAndRefreshesExpiredToken(@TempDir Path tempDir) throws Exception {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("calendar.db"));
        dbManager.initialize();
        CalendarRepository repository = new CalendarRepository(dbManager);
        CalendarService calendarService = new CalendarService(repository);
        CourseManager courseManager = new CourseManager(tempDir.resolve("courses"));

        Course course = courseManager.createCourse("CMPT 201");
        CourseItem noteItem = courseManager.createItem(
                course,
                CourseSection.LECTURES,
                "week-1.md",
                "# Pointers and Memory\nReview stack vs heap."
        );

        Path tempPdf = tempDir.resolve("slides-01.pdf");
        Files.writeString(tempPdf, "%PDF-1.4\n%%EOF\n", StandardCharsets.US_ASCII);
        courseManager.importExternalFile(course, CourseSection.LECTURES, tempPdf);

        LocalDate weekStart = LocalDate.of(2026, 11, 16);
        calendarService.saveManualEvent(CalendarEvent.create(
                "CMPT 201 Lecture",
                weekStart.atTime(LocalTime.of(10, 0)),
                weekStart.atTime(LocalTime.of(11, 30)),
                EventCategory.STUDY,
                EventSource.MANUAL
        ));

        List<String> createdNotebooks = new ArrayList<>();
        List<String> textSources = new ArrayList<>();
        List<String> binarySources = new ArrayList<>();
        AtomicInteger rejectOnceFlag = new AtomicInteger(0);
        AtomicInteger notebookSeq = new AtomicInteger(1);

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange ->
                sendJson(exchange, 200, "{\"access_token\":\"fresh-token\",\"expires_in\":3600}"));
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (rejectOnceFlag.get() == 1 && "Bearer initial-token".equals(auth)) {
                sendJson(exchange, 401, "{\"error\":{\"message\":\"Expired token\"}}");
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("GET".equals(method) && "/v1/notebooks".equals(path)) {
                StringBuilder nbs = new StringBuilder("[");
                for (int i = 0; i < createdNotebooks.size(); i++) {
                    if (i > 0) {
                        nbs.append(',');
                    }
                    String[] parts = createdNotebooks.get(i).split("\\|", 2);
                    nbs.append("{\"name\":\"").append(parts[0]).append("\",\"displayName\":\"").append(parts[1]).append("\"}");
                }
                nbs.append(']');
                sendJson(exchange, 200, "{\"notebooks\":" + nbs + "}");
                return;
            }
            if ("POST".equals(method) && "/v1/notebooks".equals(path)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int idx = notebookSeq.getAndIncrement();
                String nbName = "notebooks/nb-" + idx;
                String title = "Calendar Organizer - CMPT 201";
                if (body.contains("Schedule")) {
                    title = "Calendar Organizer - Schedule";
                }
                createdNotebooks.add(nbName + "|" + title);
                sendJson(exchange, 200, "{\"name\":\"" + nbName + "\",\"displayName\":\"" + title + "\"}");
                return;
            }
            if ("POST".equals(method) && path.endsWith("/sources")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                textSources.add(path + ":" + body);
                sendJson(exchange, 200, "{\"name\":\"" + path + "/s-text\",\"displayName\":\"text\"}");
                return;
            }
            if ("POST".equals(method) && path.endsWith("/sources:upload")) {
                binarySources.add(path + "?" + exchange.getRequestURI().getRawQuery());
                exchange.getRequestBody().readAllBytes();
                sendJson(exchange, 200, "{\"source\":{\"name\":\"" + path + "/s-bin\",\"displayName\":\"slides-01.pdf\"}}");
                return;
            }
            sendJson(exchange, 404, "{\"error\":{\"message\":\"Unknown endpoint\"}}");
        });
        server.start();

        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GeminiNotebookOAuth oauth = new GeminiNotebookOAuth(base + "/auth", base + "/token", () -> http, u -> {});
            GeminiNotebookClient client = new GeminiNotebookClient(base, () -> http);
            Clock fixedClock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);

            GeminiNotebookSyncService syncService = new GeminiNotebookSyncService(
                    repository,
                    calendarService,
                    courseManager,
                    oauth,
                    client,
                    fixedClock
            );

            syncService.saveClientConfig("proj-1", "client-1", "secret-1");
            repository.savePreference(GeminiNotebookSyncService.PREF_ACCESS_TOKEN, "initial-token");
            repository.savePreference(GeminiNotebookSyncService.PREF_REFRESH_TOKEN, "refresh-tok");
            repository.savePreference(GeminiNotebookSyncService.PREF_TOKEN_EXPIRES_AT, Long.toString(1_800_003_600L));

            NotebookSyncResult firstSync = syncService.syncCourse(course);
            assertEquals("Calendar Organizer - CMPT 201", firstSync.getNotebookDisplayName());
            assertEquals(2, firstSync.getUploadedCount());
            assertEquals(0, firstSync.getSkippedCount());
            assertEquals(1, textSources.size());
            assertEquals(1, binarySources.size());

            NotebookSyncResult secondSync = syncService.syncCourse(course);
            assertEquals(0, secondSync.getUploadedCount());
            assertEquals(2, secondSync.getSkippedCount());

            courseManager.saveItem(noteItem, "# Pointers and Memory\nUpdated exam notes.");
            rejectOnceFlag.set(1);
            NotebookSyncResult updatedSync = syncService.syncCourse(course);
            assertEquals(1, updatedSync.getUploadedCount());
            assertEquals(1, updatedSync.getSkippedCount());
            assertEquals("fresh-token", syncService.loadConfig().getAccessToken());

            NotebookSyncResult schedSync = syncService.syncSchedule(weekStart);
            assertEquals("Calendar Organizer - Schedule", schedSync.getNotebookDisplayName());
            assertEquals(1, schedSync.getUploadedCount());
            assertEquals(0, schedSync.getSkippedCount());
            assertTrue(textSources.get(textSources.size() - 1).contains("CMPT 201 Lecture"));

            NotebookSyncResult schedRepeat = syncService.syncSchedule(weekStart);
            assertEquals(0, schedRepeat.getUploadedCount());
            assertEquals(1, schedRepeat.getSkippedCount());
            assertEquals("https://notebooklm.google.com/notebook/nb-1", syncService.getCourseNotebookWebUrl(course));
            assertEquals("https://notebooklm.google.com/notebook/nb-2", syncService.getScheduleNotebookWebUrl());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void migratesPlaintextSqliteSecretsClearsInvalidGrantAndSplitsOversizedMarkdown(@TempDir Path tempDir) throws Exception {
        DatabaseManager dbManager = new DatabaseManager(tempDir.resolve("calendar.db"));
        dbManager.initialize();
        CalendarRepository repository = new CalendarRepository(dbManager);
        CalendarService calendarService = new CalendarService(repository);
        CourseManager courseManager = new CourseManager(tempDir.resolve("courses"));

        repository.savePreference(GeminiNotebookSyncService.PREF_PROJECT_ID, "proj-mig");
        repository.savePreference(GeminiNotebookSyncService.PREF_CLIENT_ID, "cid-mig");
        repository.savePreference(GeminiNotebookSyncService.PREF_CLIENT_SECRET, "legacy-secret");
        repository.savePreference(GeminiNotebookSyncService.PREF_ACCESS_TOKEN, "legacy-access");
        repository.savePreference(GeminiNotebookSyncService.PREF_REFRESH_TOKEN, "legacy-refresh");
        repository.savePreference(GeminiNotebookSyncService.PREF_TOKEN_EXPIRES_AT, "100");

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange ->
                sendJson(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been revoked\"}"));
        server.start();

        SecretStore vault = SecretStore.inMemory();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            GeminiNotebookOAuth oauth = new GeminiNotebookOAuth(base + "/auth", base + "/token", () -> http, u -> {});
            GeminiNotebookClient client = new GeminiNotebookClient(base, () -> http);
            Clock fixedClock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);

            GeminiNotebookSyncService syncService = new GeminiNotebookSyncService(
                    repository, calendarService, courseManager, oauth, client, vault, fixedClock
            );

            NotebookConfig migrated = syncService.loadConfig();
            assertEquals("legacy-secret", migrated.getClientSecret());
            assertEquals("legacy-access", migrated.getAccessToken());
            assertEquals("legacy-refresh", migrated.getRefreshToken());
            assertEquals("", repository.loadPreference(GeminiNotebookSyncService.PREF_CLIENT_SECRET, ""));
            assertEquals("", repository.loadPreference(GeminiNotebookSyncService.PREF_ACCESS_TOKEN, ""));
            assertEquals("", repository.loadPreference(GeminiNotebookSyncService.PREF_REFRESH_TOKEN, ""));
            assertEquals("legacy-secret", vault.get(GeminiNotebookSyncService.PREF_CLIENT_SECRET));

            Course bigCourse = courseManager.createCourse("BIO 101");
            String bigBlock = "# Biology Notes\n" + ("Line with cell respiration notes.\n".repeat(40));
            List<String> parts = GeminiNotebookSyncService.splitMarkdownChunks(bigBlock, 1024);
            assertTrue(parts.size() >= 2);
            for (String p : parts) {
                assertTrue(p.getBytes(StandardCharsets.UTF_8).length <= 1024);
            }

            NotebookException err = org.junit.jupiter.api.Assertions.assertThrows(
                    NotebookException.class,
                    () -> syncService.syncCourse(bigCourse)
            );
            assertTrue(err.isInvalidGrant());
            assertEquals("", syncService.loadConfig().getRefreshToken());
            assertEquals("", syncService.loadConfig().getAccessToken());
            assertEquals("cid-mig", syncService.loadConfig().getClientId());
        } finally {
            server.stop(0);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
