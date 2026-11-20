package com.calendarorganizer.llm;

import com.calendarorganizer.parser.ParseResult;
import com.google.gson.JsonArray;
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

public class GeminiProvider implements LlmProvider {

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    public static final String DEFAULT_MODEL = "gemini-2.5-flash";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final LlmPromptBuilder promptBuilder;
    private final LlmJsonParser jsonParser;

    public GeminiProvider() {
        this(System.getenv("GEMINI_API_KEY"),
                readEnv("GEMINI_MODEL", DEFAULT_MODEL),
                DEFAULT_BASE_URL,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public GeminiProvider(String apiKey, String model, String baseUrl, HttpClient httpClient) {
        if (apiKey == null) {
            this.apiKey = "";
        } else {
            this.apiKey = apiKey.trim();
        }
        if (model == null || model.isBlank()) {
            this.model = DEFAULT_MODEL;
        } else {
            this.model = model.trim();
        }
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.httpClient = httpClient;
        this.promptBuilder = new LlmPromptBuilder();
        this.jsonParser = new LlmJsonParser();
    }

    @Override
    public String getName() {
        return "gemini";
    }

    @Override
    public boolean isAvailable() {
        return !apiKey.isBlank();
    }

    @Override
    public ParseResult parse(String input, LocalDate referenceDate) throws LlmException {
        if (!isAvailable()) {
            throw new LlmException("GEMINI_API_KEY is not set.");
        }

        JsonObject payload = buildRequestPayload(promptBuilder.buildPrompt(input, referenceDate));
        String endpoint = baseUrl + "/models/" + model + ":generateContent";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException("Gemini returned HTTP " + response.statusCode());
            }
            String text = extractCandidateText(response.body());
            return jsonParser.parse(text, getName(), referenceDate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Gemini request interrupted.", e);
        } catch (IOException e) {
            throw new LlmException("Gemini request failed: " + e.getMessage(), e);
        }
    }

    private JsonObject buildRequestPayload(String prompt) {
        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);

        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("responseMimeType", "application/json");
        generationConfig.addProperty("temperature", 0.1);

        JsonObject root = new JsonObject();
        root.add("contents", contents);
        root.add("generationConfig", generationConfig);
        return root;
    }

    private String extractCandidateText(String body) throws LlmException {
        JsonElement rootEl = JsonParser.parseString(body);
        if (!rootEl.isJsonObject()) {
            throw new LlmException("Gemini response was not a JSON object.");
        }
        JsonObject root = rootEl.getAsJsonObject();
        if (!root.has("candidates") || !root.get("candidates").isJsonArray()) {
            throw new LlmException("Gemini response missing candidates.");
        }
        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates.isEmpty()) {
            throw new LlmException("Gemini returned no candidates.");
        }
        JsonObject firstCandidate = candidates.get(0).getAsJsonObject();
        if (!firstCandidate.has("content") || !firstCandidate.get("content").isJsonObject()) {
            throw new LlmException("Gemini candidate missing content.");
        }
        JsonObject content = firstCandidate.getAsJsonObject("content");
        if (!content.has("parts") || !content.get("parts").isJsonArray()) {
            throw new LlmException("Gemini candidate missing parts.");
        }
        JsonArray parts = content.getAsJsonArray("parts");
        if (parts.isEmpty()) {
            throw new LlmException("Gemini candidate had empty parts.");
        }
        JsonObject firstPart = parts.get(0).getAsJsonObject();
        if (!firstPart.has("text") || firstPart.get("text").isJsonNull()) {
            throw new LlmException("Gemini part missing text.");
        }
        return firstPart.get("text").getAsString();
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
            return DEFAULT_BASE_URL;
        }
        String clean = url.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }
}
