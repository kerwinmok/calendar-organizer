package com.calendarorganizer.llm;

import com.calendarorganizer.parser.ParseResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaProviderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void checksAvailabilityAndParsesGenerateResponse() throws IOException, LlmException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/generate", exchange -> {
            String innerJson = "{\"events\":[{\"title\":\"Gym\",\"date\":\"2026-11-16\",\"startTime\":\"18:00\",\"endTime\":\"19:00\",\"category\":\"WORKOUT\"}],\"recurringRules\":[],\"goals\":[],\"sleepTime\":\"23:30\"}";
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("response", innerJson);
            byte[] body = wrapper.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        OllamaProvider provider = new OllamaProvider(baseUrl, "llama3.2", HttpClient.newHttpClient());

        assertTrue(provider.isAvailable());
        ParseResult result = provider.parse("gym at 6pm", LocalDate.of(2026, 11, 16));
        assertEquals("ollama", result.getParserUsed());
        assertEquals(1, result.getEvents().size());
        assertEquals("Gym", result.getEvents().get(0).getTitle());
        assertEquals(LocalTime.of(23, 30), result.getSleepTime());
    }

    @Test
    void reportsUnavailableAndThrowsOnHttpError() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> exchange.sendResponseHeaders(503, -1));
        server.createContext("/api/generate", exchange -> exchange.sendResponseHeaders(500, -1));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        OllamaProvider provider = new OllamaProvider(baseUrl, "llama3.2", HttpClient.newHttpClient());

        assertFalse(provider.isAvailable());
        assertThrows(LlmException.class, () -> provider.parse("work mon 9-5", LocalDate.of(2026, 11, 16)));
    }
}
