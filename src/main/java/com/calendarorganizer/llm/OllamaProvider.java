package com.calendarorganizer.llm;

import com.calendarorganizer.parser.ParseResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

public class OllamaProvider implements LlmProvider {

    public static final String DEFAULT_HOST = "http://localhost:11434";
    public static final String DEFAULT_MODEL = "llama3.2";

    private final String host;
    private final String model;
    private final HttpClient httpClient;
    private final LlmPromptBuilder promptBuilder;
    private final LlmJsonParser jsonParser;

    public OllamaProvider() {
        this(readEnv("OLLAMA_HOST", DEFAULT_HOST),
                readEnv("OLLAMA_MODEL", DEFAULT_MODEL),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    public OllamaProvider(String host, String model, HttpClient httpClient) {
        this.host = trimTrailingSlash(host);
        this.model = model;
        this.httpClient = httpClient;
        this.promptBuilder = new LlmPromptBuilder();
        this.jsonParser = new LlmJsonParser();
    }

    @Override
    public String getName() {
        return "ollama";
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(host + "/api/tags"))
                    .timeout(Duration.ofMillis(1500))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ParseResult parse(String input, LocalDate referenceDate) throws LlmException {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("prompt", promptBuilder.buildPrompt(input, referenceDate));
        payload.addProperty("format", "json");
        payload.addProperty("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/api/generate"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException("Ollama returned HTTP " + response.statusCode());
            }
            JsonElement rootEl = JsonParser.parseString(response.body());
            if (!rootEl.isJsonObject()) {
                throw new LlmException("Ollama returned unexpected JSON structure.");
            }
            JsonObject root = rootEl.getAsJsonObject();
            if (!root.has("response") || root.get("response").isJsonNull()) {
                throw new LlmException("Ollama response missing 'response' field.");
            }
            String content = root.get("response").getAsString();
            return jsonParser.parse(content, getName(), referenceDate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Ollama request interrupted.", e);
        } catch (IOException e) {
            throw new LlmException("Ollama request failed: " + e.getMessage(), e);
        }
    }

    private static String readEnv(String key, String fallback) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            return fallback;
        }
        return val.trim();
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_HOST;
        }
        String clean = url.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }
}
