package com.calendarorganizer.notebook;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiNotebookClientTest {

    @Test
    void listsNotebooksWithPaginationCreatesSourcesAndUploadsBinaryFiles(@TempDir Path tempDir) throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> projectHeader = new AtomicReference<>();
        AtomicReference<String> uploadQuery = new AtomicReference<>();
        AtomicReference<String> uploadContentType = new AtomicReference<>();
        AtomicReference<byte[]> uploadedBytes = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            projectHeader.set(exchange.getRequestHeaders().getFirst("x-goog-user-project"));

            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();

            if ("GET".equals(method) && "/v1/notebooks".equals(path)) {
                if (query != null && query.contains("pageToken=p2")) {
                    sendJson(exchange, 200, "{\"notebooks\":[{\"name\":\"notebooks/nb-2\",\"displayName\":\"Second\"}]}");
                } else {
                    sendJson(exchange, 200, "{\"notebooks\":[{\"name\":\"notebooks/nb-1\",\"display_name\":\"First\"}],\"nextPageToken\":\"p2\"}");
                }
                return;
            }
            if ("POST".equals(method) && "/v1/notebooks".equals(path)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("CMPT 201"));
                sendJson(exchange, 200, "{\"name\":\"notebooks/nb-new\",\"displayName\":\"CMPT 201\"}");
                return;
            }
            if ("POST".equals(method) && "/v1/notebooks/nb-new/sources".equals(path)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("text/markdown"));
                assertTrue(body.contains("Lecture summary"));
                sendJson(exchange, 200, "{\"name\":\"notebooks/nb-new/sources/s-text\",\"displayName\":\"Notes.md\"}");
                return;
            }
            if ("POST".equals(method) && "/upload/v1/notebooks/nb-new/sources:upload".equals(path)) {
                uploadQuery.set(query);
                uploadContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                uploadedBytes.set(exchange.getRequestBody().readAllBytes());
                sendJson(exchange, 200, "{\"source\":{\"name\":\"notebooks/nb-new/sources/s-pdf\",\"displayName\":\"Lecture 1.pdf\"}}");
                return;
            }
            sendJson(exchange, 404, "{\"error\":{\"message\":\"Unhandled route\"}}");
        });
        server.start();

        try {
            HttpClient http = HttpClient.newHttpClient();
            GeminiNotebookClient client = new GeminiNotebookClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    () -> http
            );

            List<NotebookInfo> notebooks = client.listNotebooks("tok-1", "proj-1");
            assertEquals(2, notebooks.size());
            assertEquals("notebooks/nb-1", notebooks.get(0).getName());
            assertEquals("First", notebooks.get(0).getDisplayName());
            assertEquals("Second", notebooks.get(1).getDisplayName());
            assertEquals("Bearer tok-1", authHeader.get());
            assertEquals("proj-1", projectHeader.get());

            NotebookInfo created = client.createNotebook("tok-1", "proj-1", "CMPT 201");
            assertEquals("notebooks/nb-new", created.getName());

            NotebookSourceInfo textSrc = client.addTextSource(
                    "tok-1",
                    "proj-1",
                    created.getName(),
                    "Notes.md",
                    "text/markdown",
                    "# Lecture summary"
            );
            assertEquals("notebooks/nb-new/sources/s-text", textSrc.getName());

            Path pdf = tempDir.resolve("Lecture 1.pdf");
            byte[] pdfBytes = "%PDF-1.4 binary".getBytes(StandardCharsets.US_ASCII);
            Files.write(pdf, pdfBytes);

            NotebookSourceInfo fileSrc = client.uploadFileSource(
                    "tok-1",
                    "proj-1",
                    created.getName(),
                    "Lecture 1.pdf",
                    "application/pdf",
                    pdf
            );
            assertEquals("notebooks/nb-new/sources/s-pdf", fileSrc.getName());
            assertEquals("application/pdf", uploadContentType.get());
            assertTrue(uploadQuery.get().contains("uploadType=media"));
            assertTrue(uploadQuery.get().contains("source.displayName=Lecture%201.pdf"));
            assertEquals(pdfBytes.length, uploadedBytes.get().length);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsWorkspaceAndAccountErrorsToClearMessages() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String project = exchange.getRequestHeaders().getFirst("x-goog-user-project");
            if ("ws".equals(project)) {
                sendJson(exchange, 403, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"WORKSPACE_ACCOUNT_NOT_SUPPORTED\"}}");
            } else if ("noacct".equals(project)) {
                sendJson(exchange, 404, "{\"error\":{\"status\":\"NOT_FOUND\",\"message\":\"Account not found for user\"}}");
            } else {
                sendJson(exchange, 401, "{\"error\":{\"message\":\"Invalid credentials\"}}");
            }
        });
        server.start();

        try {
            HttpClient http = HttpClient.newHttpClient();
            GeminiNotebookClient client = new GeminiNotebookClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    () -> http
            );

            NotebookException wsEx = assertThrows(NotebookException.class, () -> client.listNotebooks("tok", "ws"));
            assertEquals(403, wsEx.getStatusCode());
            assertTrue(wsEx.getMessage().contains("personal Google account"));

            NotebookException acctEx = assertThrows(NotebookException.class, () -> client.listNotebooks("tok", "noacct"));
            assertEquals(404, acctEx.getStatusCode());
            assertTrue(acctEx.getMessage().contains("notebooklm.google.com"));

            NotebookException authEx = assertThrows(NotebookException.class, () -> client.listNotebooks("tok", "expired"));
            assertEquals(401, authEx.getStatusCode());
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
