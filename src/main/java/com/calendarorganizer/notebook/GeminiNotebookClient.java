package com.calendarorganizer.notebook;

import com.calendarorganizer.service.CalendarService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public class GeminiNotebookClient {

    public static final String DEFAULT_BASE_URL = "https://notebook.googleapis.com";
    public static final long MAX_UPLOAD_BYTES = 200L * 1024L * 1024L;

    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final Supplier<HttpClient> httpSupplier;

    public GeminiNotebookClient() {
        this(DEFAULT_BASE_URL, CalendarService::sharedHttpClient);
    }

    public GeminiNotebookClient(String baseUrl, Supplier<HttpClient> httpSupplier) {
        if (baseUrl == null || baseUrl.isBlank()) {
            this.baseUrl = DEFAULT_BASE_URL;
        } else {
            this.baseUrl = stripTrailingSlash(baseUrl.trim());
        }
        this.httpSupplier = httpSupplier;
    }

    public List<NotebookInfo> listNotebooks(String accessToken, String projectId) throws IOException {
        validateAuth(accessToken, projectId);
        List<NotebookInfo> results = new ArrayList<>();
        String pageToken = "";

        while (true) {
            StringBuilder url = new StringBuilder(baseUrl).append("/v1/notebooks?pageSize=100");
            if (!pageToken.isBlank()) {
                url.append("&pageToken=").append(urlEncode(pageToken));
            }
            JsonObject root = sendJsonGet(url.toString(), accessToken, projectId);
            if (root.has("notebooks") && root.get("notebooks").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("notebooks");
                for (JsonElement el : arr) {
                    if (el.isJsonObject()) {
                        results.add(parseNotebook(el.getAsJsonObject()));
                    }
                }
            }
            pageToken = readString(root, "nextPageToken");
            if (pageToken.isBlank()) {
                break;
            }
        }
        return results;
    }

    public NotebookInfo createNotebook(String accessToken, String projectId, String displayName) throws IOException {
        validateAuth(accessToken, projectId);
        String cleanName = nonBlankOrDefault(displayName, "Calendar Organizer");

        JsonObject payload = new JsonObject();
        payload.addProperty("display_name", cleanName);

        String url = baseUrl + "/v1/notebooks";
        JsonObject root = sendJsonPost(url, accessToken, projectId, payload);
        return parseNotebook(root);
    }

    public List<NotebookSourceInfo> listSources(String accessToken, String projectId, String notebookName) throws IOException {
        validateAuth(accessToken, projectId);
        String cleanParent = cleanResourcePath(notebookName);
        List<NotebookSourceInfo> results = new ArrayList<>();
        String pageToken = "";

        while (true) {
            StringBuilder url = new StringBuilder(baseUrl)
                    .append("/v1/")
                    .append(cleanParent)
                    .append("/sources?pageSize=100");
            if (!pageToken.isBlank()) {
                url.append("&pageToken=").append(urlEncode(pageToken));
            }
            JsonObject root = sendJsonGet(url.toString(), accessToken, projectId);
            if (root.has("sources") && root.get("sources").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("sources");
                for (JsonElement el : arr) {
                    if (el.isJsonObject()) {
                        results.add(parseSource(el.getAsJsonObject()));
                    }
                }
            }
            pageToken = readString(root, "nextPageToken");
            if (pageToken.isBlank()) {
                break;
            }
        }
        return results;
    }

    public NotebookSourceInfo addTextSource(
            String accessToken,
            String projectId,
            String notebookName,
            String displayName,
            String mimeType,
            String content
    ) throws IOException {
        validateAuth(accessToken, projectId);
        String cleanParent = cleanResourcePath(notebookName);

        JsonObject textContent = new JsonObject();
        if (content == null) {
            textContent.addProperty("content", "");
        } else {
            textContent.addProperty("content", content);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("display_name", nonBlankOrDefault(displayName, "Notes.md"));
        payload.addProperty("mime_type", nonBlankOrDefault(mimeType, "text/markdown"));
        payload.add("text_content", textContent);

        String url = baseUrl + "/v1/" + cleanParent + "/sources";
        JsonObject root = sendJsonPost(url, accessToken, projectId, payload);
        return parseSource(root);
    }

    public NotebookSourceInfo uploadFileSource(
            String accessToken,
            String projectId,
            String notebookName,
            String displayName,
            String mimeType,
            Path filePath
    ) throws IOException {
        validateAuth(accessToken, projectId);
        if (filePath == null || !Files.isRegularFile(filePath)) {
            throw new NotebookException(0, "File does not exist for upload.");
        }
        long fileSize = Files.size(filePath);
        if (fileSize > MAX_UPLOAD_BYTES) {
            throw new NotebookException(413, "File exceeds Gemini Notebook 200 MiB upload limit: " + filePath.getFileName());
        }

        String cleanParent = cleanResourcePath(notebookName);
        String cleanName = nonBlankOrDefault(displayName, filePath.getFileName().toString());
        String encodedDisplayName = urlEncode(cleanName).replace("+", "%20");
        String url = baseUrl + "/upload/v1/" + cleanParent
                + "/sources:upload?uploadType=media&source.displayName=" + encodedDisplayName;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + accessToken.trim())
                .header("x-goog-user-project", projectId.trim())
                .header("Content-Type", nonBlankOrDefault(mimeType, "application/octet-stream"))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofFile(filePath))
                .build();

        JsonObject root = executeAndParseJson(request);
        if (root.has("source") && root.get("source").isJsonObject()) {
            return parseSource(root.getAsJsonObject("source"));
        }
        return parseSource(root);
    }

    private JsonObject sendJsonGet(String url, String accessToken, String projectId) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + accessToken.trim())
                .header("x-goog-user-project", projectId.trim())
                .header("Accept", "application/json")
                .GET()
                .build();
        return executeAndParseJson(request);
    }

    private JsonObject sendJsonPost(
            String url,
            String accessToken,
            String projectId,
            JsonObject payload
    ) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken.trim())
                .header("x-goog-user-project", projectId.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        return executeAndParseJson(request);
    }

    private JsonObject executeAndParseJson(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = httpSupplier.get()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body();
            if (status < 200 || status >= 300) {
                throw new NotebookException(status, buildFriendlyHttpError(status, body));
            }
            if (body == null || body.isBlank()) {
                return new JsonObject();
            }
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new NotebookException(500, "Gemini Notebook API returned non-object JSON.");
            }
            return parsed.getAsJsonObject();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NotebookException(0, "Gemini Notebook request was interrupted.", ex);
        } catch (NotebookException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new NotebookException(0, "Network error calling Gemini Notebook API: " + ex.getMessage(), ex);
        }
    }

    private String buildFriendlyHttpError(int status, String body) {
        String apiMessage = extractApiErrorMessage(body);
        String upper = apiMessage.toUpperCase(Locale.ROOT);
        if (body != null && body.toUpperCase(Locale.ROOT).contains("WORKSPACE_ACCOUNT_NOT_SUPPORTED")) {
            return "Google Workspace accounts are not yet supported by Gemini Notebook API. Sign in with a personal Google account.";
        }
        if (upper.contains("ACCOUNT_NOT_FOUND") || (status == 404 && upper.contains("NOT FOUND FOR USER"))) {
            return "Gemini Notebook account not initialized yet. Open notebooklm.google.com in your browser once with this Google account, then try again.";
        }
        if (status == 401) {
            return "Google OAuth token expired or was revoked. Open Connect Gemini Notebook and sign in again.";
        }
        if (status == 429) {
            return "Gemini Notebook upload rate limit reached (max 20/minute). Wait briefly and try again.";
        }
        if (!apiMessage.isBlank()) {
            return "Gemini Notebook API error (" + status + "): " + apiMessage;
        }
        return "Gemini Notebook API failed with HTTP " + status + ".";
    }

    private String extractApiErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) {
                return "";
            }
            JsonObject root = el.getAsJsonObject();
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject err = root.getAsJsonObject("error");
                String msg = readString(err, "message");
                if (!msg.isBlank()) {
                    return msg;
                }
            }
            return readString(root, "message");
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private NotebookInfo parseNotebook(JsonObject obj) {
        String name = readString(obj, "name");
        String displayName = readString(obj, "displayName");
        if (displayName.isBlank()) {
            displayName = readString(obj, "display_name");
        }
        if (displayName.isBlank()) {
            displayName = readString(obj, "title");
        }
        return new NotebookInfo(name, displayName);
    }

    private NotebookSourceInfo parseSource(JsonObject obj) {
        String name = readString(obj, "name");
        String displayName = readString(obj, "displayName");
        if (displayName.isBlank()) {
            displayName = readString(obj, "display_name");
        }
        if (displayName.isBlank()) {
            displayName = readString(obj, "title");
        }
        return new NotebookSourceInfo(name, displayName);
    }

    private void validateAuth(String accessToken, String projectId) throws NotebookException {
        if (accessToken == null || accessToken.isBlank()) {
            throw new NotebookException(401, "Not signed in to Gemini Notebook.");
        }
        if (projectId == null || projectId.isBlank()) {
            throw new NotebookException(400, "Google Cloud Project ID is required for Gemini Notebook API.");
        }
    }

    private static String cleanResourcePath(String resourcePath) throws NotebookException {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new NotebookException(400, "Notebook resource name is missing.");
        }
        String trimmed = resourcePath.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            throw new NotebookException(400, "Notebook resource name is invalid.");
        }
        return trimmed;
    }

    private static String stripTrailingSlash(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String readString(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return "";
        }
        JsonElement el = obj.get(field);
        if (!el.isJsonPrimitive()) {
            return "";
        }
        return el.getAsString().trim();
    }
}
