package com.calendarorganizer.notebook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiNotebookOAuthTest {

    @Test
    void parsesGoogleClientSecretJsonAndExtractsEmailFromIdToken() {
        String installedJson = """
                {
                  "installed": {
                    "client_id": "12345.apps.googleusercontent.com",
                    "project_id": "cal-org-demo",
                    "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                    "token_uri": "https://oauth2.googleapis.com/token",
                    "client_secret": "GOCSPX-example-secret"
                  }
                }
                """;
        NotebookConfig.ClientCredentials creds = NotebookConfig.parseGoogleClientSecretJson(installedJson);
        assertEquals("cal-org-demo", creds.getProjectId());
        assertEquals("12345.apps.googleusercontent.com", creds.getClientId());
        assertEquals("GOCSPX-example-secret", creds.getClientSecret());

        assertThrows(IllegalArgumentException.class,
                () -> NotebookConfig.parseGoogleClientSecretJson("{\"installed\":{\"client_id\":\"abc\"}}"));

        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"email\":\"student@gmail.com\"}".getBytes(StandardCharsets.UTF_8));
        String fakeJwt = header + "." + payload + ".sig";
        assertEquals("student@gmail.com", NotebookConfig.extractEmailFromIdToken(fakeJwt));
    }

    @Test
    void completesLoopbackPkceFlowAndRefreshesToken() throws Exception {
        AtomicReference<Map<String, String>> capturedTokenForm = new AtomicReference<>();
        AtomicReference<String> capturedAuthorizeUrl = new AtomicReference<>();
        AtomicReference<Integer> callbackStatus = new AtomicReference<>(0);

        HttpServer tokenServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"email\":\"kerwin@gmail.com\"}".getBytes(StandardCharsets.UTF_8));
        String fakeIdToken = header + "." + payload + ".sig";

        tokenServer.createContext("/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> parsed = parseQuery(form);
            capturedTokenForm.set(parsed);

            String grantType = parsed.getOrDefault("grant_type", "");
            String json;
            if ("refresh_token".equals(grantType)) {
                json = "{\"access_token\":\"access-2\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
            } else {
                json = "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\",\"expires_in\":3600,\"id_token\":\"" + fakeIdToken + "\"}";
            }
            sendJson(exchange, 200, json);
        });
        tokenServer.start();

        try {
            HttpClient http = HttpClient.newHttpClient();
            String tokenUrl = "http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/token";

            GeminiNotebookOAuth oauth = new GeminiNotebookOAuth(
                    "https://accounts.google.com/o/oauth2/v2/auth",
                    tokenUrl,
                    () -> http,
                    url -> {
                        capturedAuthorizeUrl.set(url);
                        Thread t = new Thread(() -> {
                            try {
                                Map<String, String> authParams = parseQuery(URI.create(url).getRawQuery());
                                String callback = authParams.get("redirect_uri")
                                        + "?code=auth-code-99&state=" + authParams.get("state");
                                HttpResponse<String> cbResp = http.send(
                                        HttpRequest.newBuilder().uri(URI.create(callback)).GET().build(),
                                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                                );
                                callbackStatus.set(cbResp.statusCode());
                            } catch (Exception ignored) {
                            }
                        });
                        t.setDaemon(true);
                        t.start();
                    }
            );

            GeminiNotebookOAuth.OAuthTokens tokens = oauth.authorizeInBrowser(
                    "client-123",
                    "secret-456",
                    Duration.ofSeconds(5),
                    new AtomicBoolean(false)
            );

            assertEquals(200, callbackStatus.get());
            assertEquals("access-1", tokens.getAccessToken());
            assertEquals("refresh-1", tokens.getRefreshToken());
            assertEquals("kerwin@gmail.com", tokens.getAccountEmail());

            Map<String, String> authQuery = parseQuery(URI.create(capturedAuthorizeUrl.get()).getRawQuery());
            Map<String, String> tokenForm = capturedTokenForm.get();
            assertEquals("S256", authQuery.get("code_challenge_method"));
            assertEquals("auth-code-99", tokenForm.get("code"));
            assertEquals("authorization_code", tokenForm.get("grant_type"));

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String expectedChallenge = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(md.digest(tokenForm.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
            assertEquals(expectedChallenge, authQuery.get("code_challenge"));

            GeminiNotebookOAuth.OAuthTokens refreshed = oauth.refreshAccessToken("client-123", "secret-456", tokens.getRefreshToken());
            assertEquals("access-2", refreshed.getAccessToken());
            assertEquals("refresh-1", refreshed.getRefreshToken());
            assertTrue(refreshed.getExpiresAtEpochSeconds() > 0L);
        } finally {
            tokenServer.stop(0);
        }
    }

    @Test
    void rejectsStateMismatchDuringLoopbackCallback() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        GeminiNotebookOAuth oauth = new GeminiNotebookOAuth(
                "https://accounts.google.com/o/oauth2/v2/auth",
                "http://127.0.0.1:1/unused",
                () -> http,
                url -> {
                    Thread t = new Thread(() -> {
                        try {
                            Map<String, String> authParams = parseQuery(URI.create(url).getRawQuery());
                            String callback = authParams.get("redirect_uri") + "?code=abc&state=wrong-state";
                            http.send(
                                    HttpRequest.newBuilder().uri(URI.create(callback)).GET().build(),
                                    HttpResponse.BodyHandlers.discarding()
                            );
                        } catch (Exception ignored) {
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                }
        );

        NotebookException ex = assertThrows(NotebookException.class, () ->
                oauth.authorizeInBrowser("client-123", "", Duration.ofSeconds(5), new AtomicBoolean(false)));
        assertTrue(ex.getMessage().toLowerCase().contains("state mismatch"));
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                );
            }
        }
        return map;
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
