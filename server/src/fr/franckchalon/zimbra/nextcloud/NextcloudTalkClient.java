package fr.franckchalon.zimbra.nextcloud;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dependency-free client for the documented Nextcloud Talk and Giphy OCS APIs. */
final class NextcloudTalkClient {
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final int MAX_GIF_REDIRECTS = 4;
    private static final String TALK_V1 = "/ocs/v2.php/apps/spreed/api/v1";
    private static final String TALK_V2 = "/ocs/v2.php/apps/spreed/api/v2";
    private static final String TALK_V3 = "/ocs/v2.php/apps/spreed/api/v3";
    private static final String TALK_V4 = "/ocs/v2.php/apps/spreed/api/v4";

    @FunctionalInterface
    interface Transport {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final AppConfig config;
    private final Profile profile;
    private final String baseUrl;
    private final String basicAuth;
    private final Transport transport;

    NextcloudTalkClient(AppConfig config, Profile profile) {
        this(config, profile, defaultTransport(config));
    }

    NextcloudTalkClient(AppConfig config, Profile profile, Transport transport) {
        this.config = config;
        this.profile = profile;
        this.baseUrl = config.validateNextcloudUrl(profile.nextcloudUrl);
        this.basicAuth = "Basic " + Base64.getEncoder().encodeToString(
            (profile.username + ":" + profile.appPassword).getBytes(StandardCharsets.UTF_8)
        );
        this.transport = transport;
    }

    private static Transport defaultTransport(AppConfig config) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        return request -> client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    Map<String, Object> status() throws IOException, InterruptedException, HttpError {
        Map<String, Object> data = map(ocsData(ocsGet(
            baseUrl + "/ocs/v1.php/cloud/capabilities?format=json", "Impossible de détecter Nextcloud Talk"
        )));
        Map<String, Object> allCapabilities = map(data.get("capabilities"));
        Map<String, Object> spreed = map(allCapabilities.get("spreed"));
        List<Object> features = list(spreed.get("features"));
        Map<String, Object> configMap = map(spreed.get("config"));
        Map<String, Object> chatConfig = map(configMap.get("chat"));

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("available", !spreed.isEmpty());
        result.put("features", features);
        result.put("reactions", features.contains("reactions"));
        result.put("replies", features.contains("chat-replies"));
        result.put("deleteMessages", features.contains("delete-messages"));
        result.put("readMarker", features.contains("chat-read-marker"));
        result.put("fileSharing", features.contains("rich-object-sharing") || !features.isEmpty());
        result.put("maxMessageLength", Json.longValue(chatConfig, "max-length", 32000L));
        result.put("server", baseUrl);
        return result;
    }

    List<Map<String, Object>> conversations() throws IOException, InterruptedException, HttpError {
        String query = "/room?noStatusUpdate=1&includeStatus=true&format=json";
        Object data = null;
        HttpError lastError = null;
        for (String api : new String[]{TALK_V4, TALK_V3, TALK_V2, TALK_V1}) {
            try {
                data = ocsData(ocsGet(baseUrl + api + query, "Impossible de lire les conversations Talk"));
                break;
            } catch (HttpError e) {
                lastError = e;
                if (e.status != 404) throw e;
            }
        }
        if (data == null) throw lastError == null ? new HttpError(404, "Nextcloud Talk indisponible") : lastError;
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list(data)) {
            Map<String, Object> source = map(value);
            String token = safeToken(Json.string(source, "token", ""));
            if (token.isBlank()) continue;
            LinkedHashMap<String, Object> conversation = new LinkedHashMap<>();
            copy(conversation, source, "token", "displayName", "description", "type", "participantType",
                "participantFlags", "readOnly", "favorite", "hasPassword", "listable", "lobbyState",
                "unreadMessages", "unreadMention", "unreadMentionDirect", "lastReadMessage", "sessionId",
                "permissions", "avatarVersion", "status", "statusIcon", "statusMessage", "statusClearAt",
                "canDeleteConversation", "canLeaveConversation");
            conversation.put("lastMessage", normalizeMessage(map(source.get("lastMessage"))));
            result.add(conversation);
        }
        return result;
    }

    Map<String, Object> createConversation(int roomType, String rawRoomName, String rawInvite)
        throws IOException, InterruptedException, HttpError {
        if (roomType != 1 && roomType != 3) throw new HttpError(400, "Type de conversation Talk invalide");
        String roomName = rawRoomName == null ? "" : rawRoomName.trim();
        String invite = rawInvite == null ? "" : rawInvite.trim();
        if (roomType == 3 && (roomName.isBlank() || roomName.length() > 255)) {
            throw new HttpError(400, "Le nom de la conversation doit contenir entre 1 et 255 caractères");
        }
        if (roomType == 1 && (invite.isBlank() || invite.length() > 255)) {
            throw new HttpError(400, "Indiquez l’identifiant Nextcloud du participant");
        }
        if (containsControl(roomName) || containsControl(invite)) {
            throw new HttpError(400, "La conversation contient des caractères non autorisés");
        }

        StringBuilder form = new StringBuilder();
        appendForm(form, "roomType", String.valueOf(roomType));
        if (roomType == 1) appendForm(form, "invite", invite);
        else appendForm(form, "roomName", roomName);

        HttpError lastError = null;
        for (String api : new String[]{TALK_V4, TALK_V3, TALK_V2, TALK_V1}) {
            try {
                Object data = ocsData(ocsForm(baseUrl + api + "/room?format=json", "POST", form,
                    new int[]{200, 201}, "Impossible de créer la conversation Talk"));
                Map<String, Object> source = map(data);
                String token = safeToken(Json.string(source, "token", ""));
                if (token.isBlank()) throw new HttpError(502, "Nextcloud Talk n’a pas renvoyé la nouvelle conversation");
                LinkedHashMap<String, Object> conversation = new LinkedHashMap<>();
                copy(conversation, source, "token", "displayName", "description", "type", "participantType",
                    "participantFlags", "readOnly", "favorite", "unreadMessages", "permissions",
                    "canDeleteConversation", "canLeaveConversation");
                conversation.put("lastMessage", normalizeMessage(map(source.get("lastMessage"))));
                return conversation;
            } catch (HttpError e) {
                lastError = e;
                if (e.status != 404) throw e;
            }
        }
        throw lastError == null ? new HttpError(404, "Nextcloud Talk ne permet pas la création de conversations") : lastError;
    }

    List<Map<String, Object>> messages(String rawToken, long lastKnownMessageId, int limit, boolean future)
        throws IOException, InterruptedException, HttpError {
        String token = requiredToken(rawToken);
        int boundedLimit = Math.max(1, Math.min(200, limit));
        boolean pollFuture = future && lastKnownMessageId > 0L;
        String endpoint = baseUrl + TALK_V1 + "/chat/" + token
            + "?lookIntoFuture=" + (pollFuture ? "1" : "0")
            + (pollFuture ? "&timeout=1" : "")
            + "&setReadMarker=0&markNotificationsAsRead=0&noStatusUpdate=1&format=json"
            + "&limit=" + boundedLimit + "&lastKnownMessageId=" + Math.max(0L, lastKnownMessageId);
        HttpResponse<InputStream> response = send(request(endpoint).GET().build());
        if (response.statusCode() == 304) {
            try (InputStream ignored = response.body()) { return List.of(); }
        }
        Object data = ocsData(parseResponse(response, new int[]{200}, "Impossible de lire les messages Talk"));
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        for (Object value : list(data)) result.add(normalizeMessage(map(value)));
        return result;
    }

    Map<String, Object> sendMessage(String rawToken, String rawMessage, long replyTo)
        throws IOException, InterruptedException, HttpError {
        String token = requiredToken(rawToken);
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isBlank()) throw new HttpError(400, "Le message Talk est vide");
        if (message.length() > 32000) throw new HttpError(413, "Le message Talk est trop long");
        StringBuilder form = new StringBuilder();
        appendForm(form, "message", message);
        appendForm(form, "referenceId", UUID.randomUUID().toString().replace("-", ""));
        if (replyTo > 0L) appendForm(form, "replyTo", String.valueOf(replyTo));
        Object data = ocsData(ocsForm(baseUrl + TALK_V1 + "/chat/" + token + "?format=json",
            "POST", form, new int[]{200, 201}, "Impossible d’envoyer le message Talk"));
        return normalizeMessage(map(data));
    }

    void markRead(String rawToken, long lastReadMessage) throws IOException, InterruptedException, HttpError {
        String token = requiredToken(rawToken);
        StringBuilder form = new StringBuilder();
        if (lastReadMessage > 0L) appendForm(form, "lastReadMessage", String.valueOf(lastReadMessage));
        ocsForm(baseUrl + TALK_V1 + "/chat/" + token + "/read?format=json", "POST", form,
            new int[]{200}, "Impossible de marquer la conversation comme lue");
    }

    void setReaction(String rawToken, long messageId, String rawReaction, boolean remove)
        throws IOException, InterruptedException, HttpError {
        String token = requiredToken(rawToken);
        if (messageId <= 0L) throw new HttpError(400, "Message Talk invalide");
        String reaction = rawReaction == null ? "" : rawReaction.trim();
        if (reaction.isBlank() || reaction.codePointCount(0, reaction.length()) > 8 || reaction.length() > 32) {
            throw new HttpError(400, "Réaction Talk invalide");
        }
        StringBuilder form = new StringBuilder();
        appendForm(form, "reaction", reaction);
        ocsForm(baseUrl + TALK_V1 + "/reaction/" + token + "/" + messageId + "?format=json",
            remove ? "DELETE" : "POST", form, remove ? new int[]{200} : new int[]{200, 201},
            "Impossible de modifier la réaction Talk");
    }

    void deleteMessage(String rawToken, long messageId)
        throws IOException, InterruptedException, HttpError {
        String token = requiredToken(rawToken);
        if (messageId <= 0L) throw new HttpError(400, "Message Talk invalide");
        ocsForm(baseUrl + TALK_V1 + "/chat/" + token + "/" + messageId + "?format=json",
            "DELETE", new StringBuilder(), new int[]{200, 202}, "Impossible de supprimer le message Talk");
    }

    Map<String, Object> gifs(String rawQuery, int limit, int cursor) throws IOException, InterruptedException, HttpError {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() > 120) throw new HttpError(400, "Recherche GIF trop longue");
        int boundedLimit = Math.max(1, Math.min(24, limit));
        int boundedCursor = Math.max(0, Math.min(10000, cursor));
        String endpoint = baseUrl + "/ocs/v2.php/apps/integration_giphy/api/v1/gifs/"
            + (query.isBlank() ? "trending" : "search") + "?format=json&limit=" + boundedLimit
            + "&cursor=" + boundedCursor;
        if (!query.isBlank()) endpoint += "&term=" + encode(query);
        Map<String, Object> data = map(ocsData(ocsGet(endpoint, "GIF Nextcloud indisponible")));
        ArrayList<Map<String, Object>> entries = new ArrayList<>();
        for (Object value : list(data.get("entries"))) {
            Map<String, Object> source = map(value);
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            copy(entry, source, "title", "subline", "thumbnailUrl", "resourceUrl", "icon");
            if (!Json.string(entry, "resourceUrl", "").isBlank()) entries.add(entry);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("items", entries);
        result.put("cursor", data.get("cursor"));
        return result;
    }

    Map<String, Object> shareFile(String rawToken, String rawPath, long replyTo)
        throws IOException, InterruptedException, HttpError {
        String token = requiredToken(rawToken);
        String path = PathUtil.normalize(rawPath);
        if ("/".equals(path)) throw new HttpError(400, "Sélectionnez un fichier à partager");
        StringBuilder metadata = new StringBuilder("{\"messageType\":\"comment\"");
        if (replyTo > 0L) metadata.append(",\"replyTo\":").append(replyTo);
        metadata.append('}');
        StringBuilder form = new StringBuilder();
        appendForm(form, "shareType", "10");
        appendForm(form, "shareWith", token);
        appendForm(form, "path", path);
        appendForm(form, "referenceId", UUID.randomUUID().toString().replace("-", ""));
        appendForm(form, "talkMetaData", metadata.toString());
        Object data = ocsData(ocsForm(baseUrl + "/ocs/v2.php/apps/files_sharing/api/v1/shares?format=json",
            "POST", form, new int[]{200, 201}, "Impossible de partager le fichier dans Talk"));
        return map(data);
    }

    HttpResponse<InputStream> getGif(String rawUrl) throws IOException, InterruptedException, HttpError {
        URI candidate;
        try {
            candidate = URI.create(rawUrl == null ? "" : rawUrl);
            if (!candidate.isAbsolute()) candidate = URI.create(baseUrl + "/").resolve(candidate);
        }
        catch (IllegalArgumentException e) { throw new HttpError(400, "Adresse GIF invalide"); }
        for (int redirect = 0; redirect <= MAX_GIF_REDIRECTS; redirect++) {
            validateGifUri(candidate);
            HttpRequest.Builder builder = HttpRequest.newBuilder(candidate)
                .timeout(config.requestTimeout)
                .header("Accept", "image/avif,image/webp,image/gif,image/*;q=0.8")
                .header("User-Agent", Version.USER_AGENT);
            if (isNextcloudGifUri(candidate)) {
                builder.header("Authorization", basicAuth).header("OCS-APIRequest", "true");
            }
            HttpResponse<InputStream> response = send(builder.GET().build());
            if (isRedirect(response.statusCode())) {
                String location = response.headers().firstValue("Location").orElse("");
                try (InputStream ignored = response.body()) {
                    if (location.isBlank()) throw new HttpError(502, "Redirection GIF incomplète");
                    candidate = candidate.resolve(location);
                } catch (IllegalArgumentException e) {
                    throw new HttpError(502, "Redirection GIF invalide", e);
                }
                continue;
            }
            if (response.statusCode() != 200) {
                try (InputStream body = response.body()) {
                    throw statusError(response.statusCode(), "GIF Nextcloud indisponible", body);
                }
            }
            return response;
        }
        throw new HttpError(502, "Trop de redirections pour le GIF Nextcloud");
    }

    private void validateGifUri(URI candidate) throws HttpError {
        if (candidate == null || candidate.getUserInfo() != null || candidate.getHost() == null
                || !(isNextcloudGifUri(candidate) || isGiphyCdnUri(candidate))) {
            throw new HttpError(400, "Adresse GIF refusée");
        }
    }

    private boolean isNextcloudGifUri(URI candidate) {
        URI base = URI.create(baseUrl);
        boolean sameOrigin = base.getScheme().equalsIgnoreCase(candidate.getScheme())
            && base.getHost().equalsIgnoreCase(candidate.getHost()) && effectivePort(base) == effectivePort(candidate);
        String path = candidate.getPath() == null ? "" : candidate.getPath();
        String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        String directPrefix = basePath + "/apps/integration_giphy/";
        String frontControllerPrefix = basePath + "/index.php/apps/integration_giphy/";
        return sameOrigin && (path.startsWith(directPrefix) || path.startsWith(frontControllerPrefix));
    }

    private static boolean isGiphyCdnUri(URI candidate) {
        if (!"https".equalsIgnoreCase(candidate.getScheme()) || effectivePort(candidate) != 443) return false;
        String host = candidate.getHost() == null ? "" : candidate.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean trustedHost = "i.giphy.com".equals(host) || "images.giphy.com".equals(host)
            || host.matches("media[0-9]*\\.giphy\\.com");
        String path = candidate.getPath() == null ? "" : candidate.getPath();
        return trustedHost && (path.startsWith("/media/") || path.startsWith("/gifs/"));
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private Map<String, Object> ocsGet(String endpoint, String error)
        throws IOException, InterruptedException, HttpError {
        return parseResponse(send(request(endpoint).GET().build()), new int[]{200}, error);
    }

    private Map<String, Object> ocsForm(String endpoint, String method, StringBuilder form, int[] expected, String error)
        throws IOException, InterruptedException, HttpError {
        HttpRequest request = request(endpoint)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .method(method, HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8)).build();
        return parseResponse(send(request), expected, error);
    }

    private HttpRequest.Builder request(String endpoint) {
        return HttpRequest.newBuilder(URI.create(endpoint))
            // Talk is interactive and is served through the Zimbra proxy. A
            // bounded timeout prevents a slow Nextcloud from occupying a
            // mailboxd request until nginx returns an HTML 504 page.
            .timeout(config.talkRequestTimeout)
            .header("Authorization", basicAuth)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .header("User-Agent", Version.USER_AGENT);
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        return transport.send(request);
    }

    private static Map<String, Object> parseResponse(HttpResponse<InputStream> response, int[] expected, String error)
        throws IOException, HttpError {
        try (InputStream input = response.body()) {
            byte[] payload = input.readNBytes(MAX_JSON_BYTES + 1);
            if (payload.length > MAX_JSON_BYTES) throw new HttpError(502, "Réponse Talk trop volumineuse");
            boolean accepted = false;
            for (int status : expected) if (response.statusCode() == status) { accepted = true; break; }
            if (!accepted) throw statusError(response.statusCode(), error, new ByteArrayInputStream(payload));
            try {
                Map<String, Object> root = Json.parseObject(new String(payload, StandardCharsets.UTF_8));
                long ocsStatus = ocsStatusCode(root);
                if (ocsStatus >= 400L) throw new HttpError((int) Math.min(599L, ocsStatus), ocsMessage(root));
                return root;
            } catch (HttpError e) {
                throw e;
            } catch (IllegalArgumentException e) {
                throw new HttpError(502, "Réponse JSON de Nextcloud Talk invalide", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Object ocsData(Map<String, Object> root) {
        Object ocs = root.get("ocs");
        if (ocs instanceof Map) return ((Map<String, Object>) ocs).get("data");
        return root.get("data");
    }

    @SuppressWarnings("unchecked")
    private static long ocsStatusCode(Map<String, Object> root) {
        Object ocs = root.get("ocs");
        if (!(ocs instanceof Map)) return 100L;
        Object meta = ((Map<String, Object>) ocs).get("meta");
        return meta instanceof Map ? Json.longValue((Map<String, Object>) meta, "statuscode", 100L) : 100L;
    }

    @SuppressWarnings("unchecked")
    private static String ocsMessage(Map<String, Object> root) {
        Object ocs = root.get("ocs");
        if (!(ocs instanceof Map)) return "Nextcloud Talk refuse cette opération";
        Object meta = ((Map<String, Object>) ocs).get("meta");
        String value = meta instanceof Map ? Json.string((Map<String, Object>) meta, "message", "") : "";
        return value.isBlank() ? "Nextcloud Talk refuse cette opération" : value;
    }

    private static HttpError statusError(int status, String message, InputStream body) throws IOException {
        String detail = new String(body.readNBytes(4096), StandardCharsets.UTF_8)
            .replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (status == 401) return new HttpError(401, "Identifiant ou mot de passe d’application Nextcloud incorrect");
        if (status == 403) return new HttpError(403, "Nextcloud Talk refuse cette opération");
        if (status == 404) return new HttpError(404, "Nextcloud Talk n’est pas disponible pour ce compte");
        if (status == 409 || status == 412) return new HttpError(409, "Cette conversation Talk n’est pas accessible");
        if (status == 413) return new HttpError(413, "Le message Talk est trop long");
        if (status == 429) return new HttpError(429, "Trop de requêtes vers Nextcloud Talk");
        return new HttpError(502, message + (detail.isBlank() ? "" : " (HTTP " + status + ")"));
    }

    private static Map<String, Object> normalizeMessage(Map<String, Object> source) {
        LinkedHashMap<String, Object> message = new LinkedHashMap<>();
        if (source.isEmpty()) return message;
        copy(message, source, "id", "token", "actorType", "actorId", "actorDisplayName", "timestamp",
            "systemMessage", "messageType", "isReplyable", "referenceId", "message", "messageParameters",
            "expirationTimestamp", "reactions", "reactionsSelf", "markdown", "lastEditActorType",
            "lastEditActorId", "lastEditActorDisplayName", "lastEditTimestamp", "silent");
        Map<String, Object> parent = map(source.get("parent"));
        if (!parent.isEmpty()) message.put("parent", normalizeMessage(parent));
        return message;
    }

    private static void copy(Map<String, Object> target, Map<String, Object> source, String... names) {
        for (String name : names) if (source.containsKey(name)) target.put(name, source.get(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    private static String requiredToken(String rawToken) throws HttpError {
        String token = safeToken(rawToken);
        if (token.isBlank()) throw new HttpError(400, "Conversation Talk invalide");
        return token;
    }

    private static String safeToken(String rawToken) {
        String token = rawToken == null ? "" : rawToken.trim();
        return token.matches("[A-Za-z0-9]{4,64}") ? token : "";
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static void appendForm(StringBuilder form, String name, String value) {
        if (form.length() > 0) form.append('&');
        form.append(encode(name)).append('=').append(encode(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
