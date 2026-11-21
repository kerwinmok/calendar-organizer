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
import java.util.function.Supplier;

public class GeminiProvider implements LlmProvider {

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    public static final String DEFAULT_MODEL = "gemini-flash-lite-latest";

    private final Supplier<String> apiKeySupplier;
    private final Supplier<String> modelSupplier;
    private final String baseUrl;
    private final Supplier<HttpClient> httpClientSupplier;
    private final LlmPromptBuilder promptBuilder;
    private final LlmJsonParser jsonParser;

    public GeminiProvider() {
        this(
                () -> System.getenv("GEMINI_API_KEY"),
                () -> readEnv("GEMINI_MODEL", DEFAULT_MODEL),
                DEFAULT_BASE_URL,
                () -> HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        );
    }

    public GeminiProvider(HttpClient httpClient) {
        this(
                () -> System.getenv("GEMINI_API_KEY"),
                () -> readEnv("GEMINI_MODEL", DEFAULT_MODEL),
                DEFAULT_BASE_URL,
                () -> httpClient
        );
    }

    public GeminiProvider(String apiKey, String model, String baseUrl, HttpClient httpClient) {
        this(() -> apiKey, () -> model, baseUrl, () -> httpClient);
    }

    public GeminiProvider(Supplier<String> apiKeySupplier,
                          Supplier<String> modelSupplier,
                          String baseUrl,
                          HttpClient httpClient) {
        this(apiKeySupplier, modelSupplier, baseUrl, () -> httpClient);
    }

    public GeminiProvider(Supplier<String> apiKeySupplier,
                          Supplier<String> modelSupplier,
                          String baseUrl,
                          Supplier<HttpClient> httpClientSupplier) {
        this.apiKeySupplier = apiKeySupplier;
        this.modelSupplier = modelSupplier;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.httpClientSupplier = httpClientSupplier;
        this.promptBuilder = new LlmPromptBuilder();
        this.jsonParser = new LlmJsonParser();
    }

    @Override
    public String getName() {
        return "gemini";
    }

    @Override
    public boolean isAvailable() {
        return !resolveApiKey().isBlank();
    }

    @Override
    public ParseResult parse(String input, LocalDate referenceDate) throws LlmException {
        String apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            throw new LlmException("GEMINI_API_KEY is not set.");
        }

        JsonObject payload = buildRequestPayload(promptBuilder.buildPrompt(input, referenceDate));
        String endpoint = baseUrl + "/models/" + resolveModel() + ":generateContent";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClientSupplier.get().send(request, HttpResponse.BodyHandlers.ofString());
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
        String fallbackText = null;
        for (JsonElement element : parts) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject partObj = element.getAsJsonObject();
            if (!partObj.has("text") || partObj.get("text").isJsonNull()) {
                continue;
            }
            String text = partObj.get("text").getAsString();
            if (fallbackText == null) {
                fallbackText = text;
            }
            boolean isThought = partObj.has("thought")
                    && !partObj.get("thought").isJsonNull()
                    && partObj.get("thought").getAsBoolean();
            if (!isThought) {
                return text;
            }
        }
        if (fallbackText == null) {
            throw new LlmException("Gemini part missing text.");
        }
        return fallbackText;
    }

    private String resolveApiKey() {
        if (apiKeySupplier == null) {
            return "";
        }
        String raw = apiKeySupplier.get();
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    private String resolveModel() {
        if (modelSupplier == null) {
            return DEFAULT_MODEL;
        }
        String raw = modelSupplier.get();
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MODEL;
        }
        return raw.trim();
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
