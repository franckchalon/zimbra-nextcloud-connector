package fr.franckchalon.zimbra.nextcloud;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class NextcloudLoginFlow {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final AppConfig config;
    private final HttpClient client;

    NextcloudLoginFlow(AppConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder().connectTimeout(config.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    Map<String, Object> start(String rawUrl) throws IOException, InterruptedException, HttpError {
        String server = config.validateNextcloudUrl(rawUrl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(server + "/index.php/login/v2"))
            .timeout(config.requestTimeout).header("Accept", "application/json")
            .header("User-Agent", Version.USER_AGENT).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        Map<String, Object> result = readJson(response, "Impossible de démarrer la connexion sécurisée Nextcloud");
        Map<String, Object> poll = mapValue(result.get("poll"));
        String login = Json.string(result, "login", "").trim();
        String endpoint = Json.string(poll, "endpoint", "").trim();
        String token = Json.string(poll, "token", "").trim();
        validateSameServer(server, login);
        validateSameServer(server, endpoint);
        if (token.length() < 20 || token.length() > 512) throw new HttpError(502, "Jeton Login Flow Nextcloud invalide");
        LinkedHashMap<String, Object> flow = new LinkedHashMap<>();
        flow.put("server", server);
        flow.put("login", login);
        flow.put("pollEndpoint", endpoint);
        flow.put("pollToken", token);
        flow.put("expiresIn", 1200);
        return flow;
    }

    Map<String, Object> poll(String rawServer, String rawEndpoint, String rawToken)
        throws IOException, InterruptedException, HttpError {
        String server = config.validateNextcloudUrl(rawServer);
        validateSameServer(server, rawEndpoint);
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.length() < 20 || token.length() > 512) throw new HttpError(400, "Jeton Login Flow Nextcloud invalide");
        String form = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(rawEndpoint))
            .timeout(config.requestTimeout).header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .header("User-Agent", Version.USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 404) {
            try (InputStream body = response.body()) { body.readNBytes(8192); }
            LinkedHashMap<String, Object> pending = new LinkedHashMap<>();
            pending.put("pending", true);
            return pending;
        }
        Map<String, Object> data = readJson(response, "La connexion Nextcloud n’a pas pu être finalisée");
        String returnedServer = config.validateNextcloudUrl(Json.string(data, "server", server));
        validateSameServer(server, returnedServer);
        String username = Json.string(data, "loginName", "").trim();
        String appPassword = Json.string(data, "appPassword", "");
        if (username.isBlank() || username.length() > 320 || appPassword.isBlank() || appPassword.length() > 4096) {
            throw new HttpError(502, "Nextcloud n’a pas renvoyé des identifiants d’application valides");
        }
        LinkedHashMap<String, Object> complete = new LinkedHashMap<>();
        complete.put("pending", false);
        complete.put("server", returnedServer);
        complete.put("username", username);
        complete.put("appPassword", appPassword);
        return complete;
    }

    private static Map<String, Object> readJson(HttpResponse<InputStream> response, String error)
        throws IOException, HttpError {
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new HttpError(502, "Réponse Login Flow Nextcloud trop volumineuse");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HttpError(response.statusCode() == 401 || response.statusCode() == 403
                    ? response.statusCode() : 502, error + " (HTTP " + response.statusCode() + ")");
            }
            try { return Json.parseObject(new String(bytes, StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException e) { throw new HttpError(502, "Réponse Login Flow Nextcloud illisible", e); }
        }
    }

    private static void validateSameServer(String server, String rawUrl) throws HttpError {
        try {
            URI expected = URI.create(server);
            URI actual = URI.create(rawUrl);
            int expectedPort = expected.getPort() < 0 ? ("https".equals(expected.getScheme()) ? 443 : 80) : expected.getPort();
            int actualPort = actual.getPort() < 0 ? ("https".equals(actual.getScheme()) ? 443 : 80) : actual.getPort();
            String basePath = expected.getPath() == null ? "" : expected.getPath();
            String actualPath = actual.getPath() == null ? "" : actual.getPath();
            if (!expected.getScheme().equalsIgnoreCase(actual.getScheme())
                    || !expected.getHost().equalsIgnoreCase(actual.getHost())
                    || expectedPort != actualPort
                    || (!basePath.isBlank() && !actualPath.equals(basePath) && !actualPath.startsWith(basePath + "/"))) {
                throw new HttpError(400, "Adresse Login Flow Nextcloud inattendue");
            }
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "Adresse Login Flow Nextcloud invalide", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : java.util.Collections.emptyMap();
    }
}
