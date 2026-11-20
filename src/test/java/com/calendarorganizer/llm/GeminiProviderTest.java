package com.calendarorganizer.llm;

import com.calendarorganizer.parser.ParseResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiProviderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsApiKeyHeaderAndParsesCandidateJson() throws IOException, LlmException {
        AtomicReference<String> receivedKeyHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models/gemini-2.5-flash:generateContent", exchange -> {
            receivedKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            String modelJson = "{\"events\":[],\"recurringRules\":[{\"title\":\"Work\",\"daysOfWeek\":[\"MONDAY\"],\"startTime\":\"07:00\",\"endTime\":\"18:00\",\"category\":\"WORK\"}],\"goals\":[],\"sleepTime\":\"00:00\"}";

            JsonObject part = new JsonObject();
            part.addProperty("text", modelJson);
            JsonArray parts = new JsonArray();
            parts.add(part);
            JsonObject content = new JsonObject();
            content.add("parts", parts);
            JsonObject candidate = new JsonObject();
            candidate.add("content", content);
            JsonArray candidates = new JsonArray();
            candidates.add(candidate);
            JsonObject response = new JsonObject();
            response.add("candidates", candidates);

            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        GeminiProvider provider = new GeminiProvider("test-secret-key", "gemini-2.5-flash", baseUrl, HttpClient.newHttpClient());

        assertTrue(provider.isAvailable());
        ParseResult result = provider.parse("i work monday 7-6pm", LocalDate.of(2026, 11, 16));
        assertEquals("test-secret-key", receivedKeyHeader.get());
        assertEquals("gemini", result.getParserUsed());
        assertEquals(1, result.getRecurringRules().size());
        assertEquals(LocalTime.MIDNIGHT, result.getSleepTime());
    }

    @Test
    void handlesMissingKeyAndHttpFailure() throws IOException {
        GeminiProvider noKey = new GeminiProvider("", "gemini-2.5-flash", "http://127.0.0.1", HttpClient.newHttpClient());
        assertFalse(noKey.isAvailable());
        assertThrows(LlmException.class, () -> noKey.parse("work mon 7-6pm", LocalDate.of(2026, 11, 16)));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models/gemini-2.5-flash:generateContent", exchange -> exchange.sendResponseHeaders(401, -1));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        GeminiProvider badKey = new GeminiProvider("bad-key", "gemini-2.5-flash", baseUrl, HttpClient.newHttpClient());
        assertThrows(LlmException.class, () -> badKey.parse("work mon 7-6pm", LocalDate.of(2026, 11, 16)));
    }
}
