package fr.franckchalon.zimbra.nextcloud;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class NextcloudClient {
    private static final int MAX_WEBDAV_XML_BYTES = 32 * 1024 * 1024;
    private static final int MAX_OFFICE_CONFIG_BYTES = 4 * 1024 * 1024;
    private static final String PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<d:propfind xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\" "
        + "xmlns:nc=\"http://nextcloud.org/ns\" xmlns:ocs=\"http://open-collaboration-services.org/ns\">"
        + "<d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/><d:getcontentlength/>"
        + "<d:creationdate/><d:getlastmodified/><d:getetag/><d:quota-used-bytes/><d:quota-available-bytes/>"
        + "<oc:fileid/><oc:size/><oc:permissions/><oc:favorite/><oc:comments-count/><oc:comments-unread/>"
        + "<oc:owner-id/><oc:owner-display-name/><oc:checksums/><oc:tags/><oc:share-types/>"
        + "<nc:has-preview/><nc:mount-type/><nc:hide-download/><nc:is-mount-root/>"
        + "<nc:contained-folder-count/><nc:contained-file-count/><nc:upload_time/>"
        + "<nc:lock/><nc:lock-owner/><nc:lock-owner-displayname/><nc:lock-owner-editor/>"
        + "<nc:lock-time/><nc:lock-timeout/><ocs:share-permissions/>"
        + "</d:prop></d:propfind>";
    private static final String FAVORITES_REPORT_BODY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<oc:filter-files xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\" xmlns:nc=\"http://nextcloud.org/ns\">"
        + "<d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/><d:getcontentlength/>"
        + "<d:creationdate/><d:getlastmodified/><d:getetag/><oc:fileid/><oc:size/><oc:permissions/>"
        + "<oc:favorite/><oc:owner-id/><oc:owner-display-name/><oc:comments-count/><nc:has-preview/>"
        + "</d:prop><oc:filter-rules><oc:favorite>1</oc:favorite></oc:filter-rules></oc:filter-files>";
    private static final String VERSIONS_PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<d:propfind xmlns:d=\"DAV:\" xmlns:nc=\"http://nextcloud.org/ns\">"
        + "<d:prop><d:getcontentlength/><d:getlastmodified/><d:getetag/><nc:version-label/>"
        + "<nc:version-author/></d:prop></d:propfind>";
    private static final String COMMENTS_PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<d:propfind xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">"
        + "<d:prop><oc:id/><oc:parentId/><oc:message/><oc:verb/><oc:actorId/>"
        + "<oc:actorDisplayName/><oc:creationDateTime/><oc:latestChildDateTime/></d:prop></d:propfind>";
    private static final String TRASH_PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<d:propfind xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\" xmlns:nc=\"http://nextcloud.org/ns\">"
        + "<d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/><d:getcontentlength/>"
        + "<d:getlastmodified/><d:getetag/><oc:size/><nc:trashbin-filename/>"
        + "<nc:trashbin-original-location/><nc:trashbin-deletion-time/></d:prop></d:propfind>";

    private final AppConfig config;
    private final Profile profile;
    @FunctionalInterface
    interface Transport {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final Transport transport;
    private final String basicAuth;
    private final String baseUrl;
    private final String davRoot;
    private final String davBase;

    NextcloudClient(AppConfig config, Profile profile) {
        this(config, profile, defaultTransport(config));
    }

    NextcloudClient(AppConfig config, Profile profile, Transport transport) {
        this.config = config;
        this.profile = profile;
        this.baseUrl = config.validateNextcloudUrl(profile.nextcloudUrl);
        this.davRoot = baseUrl + "/remote.php/dav";
        this.davBase = davRoot + "/files/" + PathUtil.encodeSegment(profile.username);
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

    List<Map<String, Object>> list(String rawPath) throws IOException, InterruptedException, HttpError {
        String path = PathUtil.normalize(rawPath);
        HttpResponse<InputStream> response = send(
            request(path, true).header("Depth", "1").header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY)).build()
        );
        try (InputStream body = response.body()) {
            expect(response.statusCode(), 207, "Impossible de lire le dossier Nextcloud", body);
            List<Map<String, Object>> parsed = parseMultistatus(body, path);
            parsed.removeIf(item -> path.equals(item.get("path")));
            parsed.sort(Comparator
                .<Map<String, Object>, Boolean>comparing(item -> !(Boolean) item.get("isDirectory"))
                .thenComparing(item -> String.valueOf(item.get("name")).toLowerCase(Locale.ROOT)));
            return parsed;
        }
    }

    Map<String, Object> stat(String rawPath) throws IOException, InterruptedException, HttpError {
        String path = PathUtil.normalize(rawPath);
        HttpResponse<InputStream> response = send(
            request(path).header("Depth", "0").header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY)).build()
        );
        try (InputStream body = response.body()) {
            expect(response.statusCode(), 207, "Fichier Nextcloud introuvable", body);
            List<Map<String, Object>> parsed = parseMultistatus(body, path);
            if (parsed.isEmpty()) throw new HttpError(404, "Fichier Nextcloud introuvable");
            return parsed.get(0);
        }
    }

    Map<String, Object> quota() throws IOException, InterruptedException, HttpError {
        Map<String, Object> root = stat("/");
        long used = longValue(root.get("quotaUsed"), -1L);
        long available = longValue(root.get("quotaAvailable"), -2L);
        LinkedHashMap<String, Object> quota = new LinkedHashMap<>();
        quota.put("used", used);
        quota.put("available", available);
        quota.put("unlimited", available == -3L);
        quota.put("known", used >= 0L && (available >= 0L || available == -3L));
        if (used >= 0L && available >= 0L) quota.put("total", used + available);
        else quota.put("total", -1L);
        return quota;
    }

    Map<String, Object> capabilities() throws IOException, InterruptedException, HttpError {
        String endpoint = baseUrl + "/ocs/v1.php/cloud/capabilities?format=json";
        Map<String, Object> root = ocsJson(absoluteRequest(endpoint)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json").GET().build(), "Impossible de lire les capacités Nextcloud");
        Map<String, Object> data = unwrapOcsData(root);
        Map<String, Object> version = mapValue(data.get("version"));
        Map<String, Object> capabilities = mapValue(data.get("capabilities"));
        Map<String, Object> sharing = mapValue(capabilities.get("files_sharing"));
        Map<String, Object> publicSharing = mapValue(sharing.get("public"));
        Map<String, Object> theming = mapValue(capabilities.get("theming"));
        long major = Json.longValue(version, "major", 0L);

        LinkedHashMap<String, Object> features = new LinkedHashMap<>();
        features.put("webdav", true);
        features.put("search", true);
        features.put("favorites", true);
        features.put("versions", major == 0L || major >= 14L);
        features.put("comments", major == 0L || major >= 14L);
        features.put("chunkedUpload", major == 0L || major >= 10L);
        features.put("sharing", !sharing.isEmpty());
        features.put("publicLinks", Boolean.TRUE.equals(publicSharing.get("enabled")));
        features.put("federatedSharing", capabilities.containsKey("files_sharing")
            && (sharing.containsKey("federation") || sharing.containsKey("federated")));
        features.put("activity", capabilities.containsKey("activity"));
        features.put("unifiedSearch", capabilities.containsKey("search") || capabilities.containsKey("fulltextsearch"));
        features.put("directDownload", capabilities.containsKey("dav"));
        features.put("office", capabilities.containsKey(profile.office.resolve(config).connectorAppId)
            || capabilities.containsKey("onlyoffice") || capabilities.containsKey("eurooffice"));

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("serverVersion", Json.string(version, "string", ""));
        result.put("serverMajor", major);
        result.put("features", features);
        LinkedHashMap<String, Object> theme = new LinkedHashMap<>();
        theme.put("name", Json.string(theming, "name", "Nextcloud"));
        theme.put("color", safeCssColor(Json.string(theming, "color", "#0082c9")));
        theme.put("colorText", safeCssColor(Json.string(theming, "color-text", "#ffffff")));
        result.put("theming", theme);
        return result;
    }

    void revokeCurrentAppPassword() throws IOException, InterruptedException, HttpError {
        String endpoint = baseUrl + "/ocs/v2.php/core/apppassword?format=json";
        ocsJson(absoluteRequest(endpoint)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .DELETE().build(), "Impossible de révoquer le mot de passe d’application Nextcloud");
    }

    List<Map<String, Object>> favorites() throws IOException, InterruptedException, HttpError {
        HttpRequest request = request("/", true)
            .header("Content-Type", "application/xml; charset=utf-8")
            .method("REPORT", HttpRequest.BodyPublishers.ofString(FAVORITES_REPORT_BODY, StandardCharsets.UTF_8))
            .build();
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            expect(response.statusCode(), 207, "Impossible de lire les favoris Nextcloud", body);
            List<Map<String, Object>> parsed = parseMultistatus(body, null);
            parsed.removeIf(item -> "/".equals(item.get("path")));
            parsed.sort(Comparator.comparing(item -> String.valueOf(item.get("name")).toLowerCase(Locale.ROOT)));
            return parsed;
        }
    }

    void setFavorite(String rawPath, boolean favorite) throws IOException, InterruptedException, HttpError {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<d:propertyupdate xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\"><d:set><d:prop>"
            + "<oc:favorite>" + (favorite ? "1" : "0") + "</oc:favorite>"
            + "</d:prop></d:set></d:propertyupdate>";
        HttpRequest request = request(PathUtil.normalize(rawPath))
            .header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPPATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        expectEmpty(send(request), new int[]{207}, "Impossible de modifier le favori Nextcloud");
    }

    Map<String, Object> createPublicShare(String rawPath, String password, String expireDate)
        throws IOException, InterruptedException, HttpError {
        String path = PathUtil.normalize(rawPath);
        String form = buildPublicShareForm(path, password, expireDate);
        String endpoint = baseUrl + "/ocs/v2.php/apps/files_sharing/api/v1/shares?format=json";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(config.requestTimeout)
            .header("Authorization", basicAuth)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .header("User-Agent", Version.USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build();
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            byte[] payload = body.readNBytes(MAX_OFFICE_CONFIG_BYTES + 1);
            if (payload.length > MAX_OFFICE_CONFIG_BYTES) throw new HttpError(502, "Réponse de partage Nextcloud trop volumineuse");
            Map<String, Object> root = parseOcsJson(payload);
            String ocsStatus = ocsStatus(root);
            long ocsCode = ocsStatusCode(root);
            String ocsDetail = ocsMessage(root).trim();
            boolean httpSuccess = response.statusCode() >= 200 && response.statusCode() < 300;
            boolean ocsSuccess = ocsStatus.isBlank()
                || ("ok".equalsIgnoreCase(ocsStatus) && (ocsCode < 0 || ocsCode == 100 || ocsCode == 200));
            if (!httpSuccess || !ocsSuccess) {
                String detail = ocsDetail.isBlank() ? "raison non communiquée par Nextcloud" : ocsDetail;
                int clientStatus = response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404
                    ? response.statusCode() : 400;
                throw new HttpError(clientStatus, "Nextcloud refuse de créer ce lien : " + detail);
            }
            Map<String, Object> data = unwrapOcsData(root);
            String url = Json.string(data, "url", "").trim();
            if (url.isBlank()) {
                String message = ocsMessage(root);
                throw new HttpError(502, message.isBlank() ? "Nextcloud n’a pas fourni le lien public" : message);
            }
            if (!url.startsWith(baseUrl + "/")) throw new HttpError(502, "Nextcloud a renvoyé un lien public d’origine inattendue");
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("url", url);
            result.put("id", data.getOrDefault("id", ""));
            result.put("token", data.getOrDefault("token", ""));
            result.put("protected", password != null && !password.isBlank());
            result.put("expireDate", expireDate == null ? "" : expireDate);
            result.put("permissions", 1);
            result.put("readOnly", true);
            return result;
        } catch (IllegalArgumentException e) {
            throw new HttpError(502, "Réponse de partage Nextcloud illisible", e);
        }
    }

    List<Map<String, Object>> listShares(String rawPath, boolean sharedWithMe)
        throws IOException, InterruptedException, HttpError {
        StringBuilder endpoint = new StringBuilder(baseUrl)
            .append("/ocs/v2.php/apps/files_sharing/api/v1/shares?format=json&reshares=true");
        if (rawPath != null && !rawPath.isBlank()) {
            endpoint.append("&path=").append(URLEncoder.encode(PathUtil.normalize(rawPath), StandardCharsets.UTF_8));
        }
        if (sharedWithMe) endpoint.append("&shared_with_me=true");
        Map<String, Object> root = ocsJson(absoluteRequest(endpoint.toString())
            .header("OCS-APIRequest", "true").header("Accept", "application/json").GET().build(),
            "Impossible de lire les partages Nextcloud");
        return normalizeShares(unwrapOcsValue(root));
    }

    Map<String, Object> createShare(String rawPath, int shareType, String shareWith, int permissions,
        String password, String expireDate, String note, String label)
        throws IOException, InterruptedException, HttpError {
        String path = PathUtil.normalize(rawPath);
        if (!Set.of(0, 1, 3, 4, 6, 7).contains(shareType)) throw new HttpError(400, "Type de partage Nextcloud invalide");
        if (shareType != 3 && (shareWith == null || shareWith.isBlank())) {
            throw new HttpError(400, "Le destinataire du partage est obligatoire");
        }
        int safePermissions = permissions <= 0 ? 1 : permissions;
        if ((safePermissions & ~31) != 0 || (safePermissions & 1) == 0) {
            throw new HttpError(400, "Permissions de partage invalides");
        }
        if (shareType == 3 && safePermissions != 1) safePermissions = 1;
        StringBuilder form = new StringBuilder();
        appendForm(form, "path", path);
        appendForm(form, "shareType", String.valueOf(shareType));
        appendForm(form, "permissions", String.valueOf(safePermissions));
        if (shareType != 3) appendForm(form, "shareWith", shareWith.trim());
        if (password != null && !password.isBlank()) appendForm(form, "password", password);
        if (expireDate != null && !expireDate.isBlank()) appendForm(form, "expireDate", expireDate);
        if (note != null && !note.isBlank()) appendForm(form, "note", limitText(note, 1000));
        if (label != null && !label.isBlank()) appendForm(form, "label", limitText(label, 255));
        if (shareType == 3) appendForm(form, "publicUpload", "false");

        String endpoint = baseUrl + "/ocs/v2.php/apps/files_sharing/api/v1/shares?format=json";
        Map<String, Object> root = ocsJson(absoluteRequest(endpoint)
            .header("OCS-APIRequest", "true").header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8)).build(),
            "Impossible de créer le partage Nextcloud");
        List<Map<String, Object>> normalized = normalizeShares(unwrapOcsValue(root));
        if (!normalized.isEmpty()) return normalized.get(0);
        Map<String, Object> data = mapValue(unwrapOcsValue(root));
        return normalizeShare(data);
    }

    Map<String, Object> updateShare(String rawId, int permissions, String password, String expireDate, String note)
        throws IOException, InterruptedException, HttpError {
        String id = validateNumericId(rawId, "partage");
        StringBuilder form = new StringBuilder();
        if (permissions > 0) {
            if ((permissions & ~31) != 0 || (permissions & 1) == 0) throw new HttpError(400, "Permissions de partage invalides");
            appendForm(form, "permissions", String.valueOf(permissions));
        }
        if (password != null) appendForm(form, "password", password);
        if (expireDate != null) appendForm(form, "expireDate", expireDate);
        if (note != null) appendForm(form, "note", limitText(note, 1000));
        if (form.length() == 0) throw new HttpError(400, "Aucune modification de partage demandée");
        String endpoint = baseUrl + "/ocs/v2.php/apps/files_sharing/api/v1/shares/" + id + "?format=json";
        Map<String, Object> root = ocsJson(absoluteRequest(endpoint)
            .header("OCS-APIRequest", "true").header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .method("PUT", HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8)).build(),
            "Impossible de modifier le partage Nextcloud");
        List<Map<String, Object>> normalized = normalizeShares(unwrapOcsValue(root));
        return normalized.isEmpty() ? new LinkedHashMap<>() : normalized.get(0);
    }

    void deleteShare(String rawId) throws IOException, InterruptedException, HttpError {
        String id = validateNumericId(rawId, "partage");
        String endpoint = baseUrl + "/ocs/v2.php/apps/files_sharing/api/v1/shares/" + id + "?format=json";
        ocsJson(absoluteRequest(endpoint).header("OCS-APIRequest", "true")
            .header("Accept", "application/json").DELETE().build(), "Impossible de supprimer le partage Nextcloud");
    }

    List<Map<String, Object>> searchSharees(String rawQuery, String itemType)
        throws IOException, InterruptedException, HttpError {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < 2 || query.length() > 100) return new ArrayList<>();
        String type = "folder".equals(itemType) ? "folder" : "file";
        String endpoint = baseUrl + "/ocs/v2.php/apps/files_sharing/api/v1/sharees?format=json&perPage=50&itemType="
            + type + "&search=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        Map<String, Object> root = ocsJson(absoluteRequest(endpoint)
            .header("OCS-APIRequest", "true").header("Accept", "application/json").GET().build(),
            "Impossible de rechercher les destinataires Nextcloud");
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        collectSharees(unwrapOcsValue(root), result, new HashSet<>());
        return result.size() > 50 ? new ArrayList<>(result.subList(0, 50)) : result;
    }

    List<Map<String, Object>> listVersions(String rawFileId)
        throws IOException, InterruptedException, HttpError {
        String fileId = validateNumericId(rawFileId, "fichier");
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        for (VersionResource resource : queryVersionResources(fileId)) result.add(resource.details);
        return result;
    }

    HttpResponse<InputStream> getVersion(String rawFileId, String rawVersionId)
        throws IOException, InterruptedException, HttpError {
        String fileId = validateNumericId(rawFileId, "fichier");
        String versionId = validateNumericId(rawVersionId, "version");
        VersionResource resource = resolveVersionResource(fileId, versionId);
        HttpResponse<InputStream> response = send(absoluteRequest(resource.uri.toString()).GET().build());
        if (response.statusCode() != 200) {
            try (InputStream body = response.body()) {
                throw statusError(response.statusCode(), "Impossible de télécharger cette version Nextcloud", body);
            }
        }
        return response;
    }

    void restoreVersion(String rawFileId, String rawVersionId)
        throws IOException, InterruptedException, HttpError {
        String fileId = validateNumericId(rawFileId, "fichier");
        String versionId = validateNumericId(rawVersionId, "version");
        String versionsBase = davRoot + "/versions/" + PathUtil.encodeSegment(profile.username);
        VersionResource resource = resolveVersionResource(fileId, versionId);
        HttpRequest request = absoluteRequest(resource.uri.toString())
            .header("Destination", versionsBase + "/restore/target")
            .header("Overwrite", "T")
            .method("MOVE", HttpRequest.BodyPublishers.noBody()).build();
        expectEmpty(send(request), new int[]{201, 204}, "Impossible de restaurer cette version Nextcloud");
    }

    private List<VersionResource> queryVersionResources(String fileId)
        throws IOException, InterruptedException, HttpError {
        String endpoint = davRoot + "/versions/" + PathUtil.encodeSegment(profile.username)
            + "/versions/" + fileId;
        HttpRequest request = absoluteRequest(endpoint)
            .header("Depth", "1").header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", HttpRequest.BodyPublishers.ofString(VERSIONS_PROPFIND_BODY, StandardCharsets.UTF_8)).build();
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            expect(response.statusCode(), 207, "Impossible de lire les versions Nextcloud", body);
            return parseVersionResources(body, endpoint);
        }
    }

    private VersionResource resolveVersionResource(String fileId, String versionId)
        throws IOException, InterruptedException, HttpError {
        for (VersionResource resource : queryVersionResources(fileId)) {
            if (versionId.equals(resource.versionId)) return resource;
        }
        throw new HttpError(404, "Élément Nextcloud introuvable");
    }

    List<Map<String, Object>> listComments(String rawFileId)
        throws IOException, InterruptedException, HttpError {
        String fileId = validateNumericId(rawFileId, "fichier");
        String endpoint = davRoot + "/comments/files/" + fileId;
        HttpRequest request = absoluteRequest(endpoint)
            .header("Depth", "1").header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", HttpRequest.BodyPublishers.ofString(COMMENTS_PROPFIND_BODY, StandardCharsets.UTF_8)).build();
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            expect(response.statusCode(), 207, "Impossible de lire les commentaires Nextcloud", body);
            return parseComments(body);
        }
    }

    void addComment(String rawFileId, String rawMessage)
        throws IOException, InterruptedException, HttpError {
        String fileId = validateNumericId(rawFileId, "fichier");
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isEmpty() || message.length() > 4000) throw new HttpError(400, "Commentaire Nextcloud invalide");
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("actorType", "users");
        payload.put("verb", "comment");
        payload.put("message", message);
        HttpRequest request = absoluteRequest(davRoot + "/comments/files/" + fileId)
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload), StandardCharsets.UTF_8)).build();
        expectEmpty(send(request), new int[]{200, 201}, "Impossible d’ajouter le commentaire Nextcloud");
    }

    void deleteComment(String rawFileId, String rawCommentId)
        throws IOException, InterruptedException, HttpError {
        String fileId = validateNumericId(rawFileId, "fichier");
        String commentId = validateNumericId(rawCommentId, "commentaire");
        HttpRequest request = absoluteRequest(davRoot + "/comments/files/" + fileId + "/" + commentId).DELETE().build();
        expectEmpty(send(request), new int[]{200, 204}, "Impossible de supprimer le commentaire Nextcloud");
    }

    List<Map<String, Object>> activity(String rawFileId)
        throws IOException, InterruptedException, HttpError {
        String endpoint = baseUrl + "/ocs/v2.php/apps/activity/api/v2/activity?format=json&limit=50";
        if (rawFileId != null && !rawFileId.isBlank()) {
            endpoint += "&object_type=files&object_id=" + validateNumericId(rawFileId, "fichier");
        }
        Map<String, Object> root = ocsJson(absoluteRequest(endpoint)
            .header("OCS-APIRequest", "true").header("Accept", "application/json").GET().build(),
            "Impossible de lire l’activité Nextcloud");
        Object data = unwrapOcsValue(root);
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        for (Object item : listValue(data)) {
            Map<String, Object> source = mapValue(item);
            if (source.isEmpty()) continue;
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("id", source.getOrDefault("activity_id", source.getOrDefault("activityId", "")));
            normalized.put("subject", source.getOrDefault("subject", ""));
            normalized.put("message", source.getOrDefault("message", ""));
            normalized.put("datetime", source.getOrDefault("datetime", source.getOrDefault("timestamp", "")));
            normalized.put("user", source.getOrDefault("user", ""));
            normalized.put("icon", source.getOrDefault("icon", ""));
            result.add(normalized);
        }
        return result;
    }

    List<Map<String, Object>> recent(int days) throws IOException, InterruptedException, HttpError {
        int safeDays = Math.max(1, Math.min(days, 365));
        String since = Instant.now().minusSeconds(safeDays * 86400L).toString();
        return advancedSearch("", "/", "all", since, -1L, -1L, 200);
    }

    List<Map<String, Object>> advancedSearch(String rawQuery, String rawScope, String category,
        String modifiedAfter, long minimumSize, long maximumSize, int limit)
        throws IOException, InterruptedException, HttpError {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() > 200) throw new HttpError(400, "Recherche trop longue");
        String scope = PathUtil.normalize(rawScope);
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        String body = buildAdvancedSearchBody(profile.username, scope, query, category,
            modifiedAfter, minimumSize, maximumSize, safeLimit);
        HttpRequest request = HttpRequest.newBuilder(URI.create(davRoot + "/"))
            .timeout(config.requestTimeout).header("Authorization", basicAuth)
            .header("User-Agent", Version.USER_AGENT).header("Accept", "application/xml")
            .header("Content-Type", "application/xml; charset=utf-8")
            .method("SEARCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<InputStream> response = send(request);
        try (InputStream responseBody = response.body()) {
            expect(response.statusCode(), 207, "La recherche avancée Nextcloud a échoué", responseBody);
            List<Map<String, Object>> parsed = parseMultistatus(responseBody, null);
            parsed.removeIf(item -> "/".equals(item.get("path")));
            parsed.sort(Comparator.comparing(item -> String.valueOf(item.get("modified")), Comparator.reverseOrder()));
            return parsed;
        }
    }

    static String buildPublicShareForm(String path, String password, String expireDate) {
        StringBuilder form = new StringBuilder();
        appendForm(form, "path", path);
        appendForm(form, "shareType", "3");
        // Nextcloud permission bit 1 = read. No create/update/delete/share bit is granted.
        appendForm(form, "permissions", "1");
        appendForm(form, "publicUpload", "false");
        if (password != null && !password.isBlank()) appendForm(form, "password", password);
        if (expireDate != null && !expireDate.isBlank()) appendForm(form, "expireDate", expireDate);
        return form.toString();
    }

    List<Map<String, Object>> listTrash() throws IOException, InterruptedException, HttpError {
        String trashBase = trashBase();
        HttpRequest request = absoluteRequest(trashBase + "/")
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", HttpRequest.BodyPublishers.ofString(TRASH_PROPFIND_BODY))
            .build();
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            expect(response.statusCode(), 207, "Impossible de lire les fichiers supprimés Nextcloud", body);
            List<Map<String, Object>> parsed = parseTrashMultistatus(body);
            parsed.removeIf(item -> "/".equals(item.get("path")));
            parsed.sort(Comparator.comparing(item -> String.valueOf(item.get("trashDeletionTime")), Comparator.reverseOrder()));
            return parsed;
        }
    }

    void restoreTrash(String rawTrashId) throws IOException, InterruptedException, HttpError {
        String trashId = validateTrashId(rawTrashId);
        String source = trashBase() + "/" + PathUtil.encodeSegment(trashId);
        String destination = davRoot + "/trashbin/" + PathUtil.encodeSegment(profile.username)
            + "/restore/" + PathUtil.encodeSegment(trashId);
        HttpRequest request = absoluteRequest(source)
            .header("Destination", destination)
            .header("Overwrite", "T")
            .method("MOVE", HttpRequest.BodyPublishers.noBody()).build();
        expectEmpty(send(request), new int[]{201, 204}, "Impossible de restaurer l’élément Nextcloud");
    }

    void deleteTrash(String rawTrashId) throws IOException, InterruptedException, HttpError {
        String trashId = validateTrashId(rawTrashId);
        HttpRequest request = absoluteRequest(trashBase() + "/" + PathUtil.encodeSegment(trashId)).DELETE().build();
        expectEmpty(send(request), new int[]{204}, "Impossible de supprimer définitivement l’élément Nextcloud");
    }

    void emptyTrash() throws IOException, InterruptedException, HttpError {
        HttpRequest request = absoluteRequest(trashBase()).DELETE().build();
        expectEmpty(send(request), new int[]{204}, "Impossible de vider les fichiers supprimés Nextcloud");
    }

    List<Map<String, Object>> search(String rawQuery, String rawScope)
        throws IOException, InterruptedException, HttpError {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) return new ArrayList<>();
        if (query.length() > 200) throw new HttpError(400, "Recherche trop longue");
        String scope = PathUtil.normalize(rawScope);
        String body = buildSearchBody(profile.username, scope, query);
        HttpRequest request = HttpRequest.newBuilder(URI.create(davRoot + "/"))
            .timeout(config.requestTimeout)
            .header("Authorization", basicAuth)
            .header("User-Agent", Version.USER_AGENT)
            .header("Accept", "application/xml")
            .header("Content-Type", "application/xml; charset=utf-8")
            .method("SEARCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<InputStream> response = send(request);
        try (InputStream responseBody = response.body()) {
            expect(response.statusCode(), 207, "La recherche globale Nextcloud a échoué", responseBody);
            List<Map<String, Object>> parsed = parseMultistatus(responseBody, null);
            parsed.sort(Comparator
                .<Map<String, Object>, Boolean>comparing(item -> !(Boolean) item.get("isDirectory"))
                .thenComparing(item -> String.valueOf(item.get("name")).toLowerCase(Locale.ROOT)));
            return parsed;
        }
    }

    HttpResponse<InputStream> thumbnail(String rawFileId)
        throws IOException, InterruptedException, HttpError {
        String fileId = rawFileId == null ? "" : rawFileId.trim();
        if (!fileId.matches("[0-9]{1,20}")) throw new HttpError(400, "Identifiant de miniature invalide");
        String endpoint = baseUrl + "/index.php/core/preview?fileId=" + PathUtil.encodeSegment(fileId)
            + "&x=256&y=256&forceIcon=0&a=0";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(config.requestTimeout)
            .header("Authorization", basicAuth)
            .header("User-Agent", Version.USER_AGENT)
            .header("Accept", "image/*")
            .GET().build();
        HttpResponse<InputStream> response = send(request);
        if (response.statusCode() != 200) {
            try (InputStream responseBody = response.body()) {
                throw new HttpError(404, "Miniature Nextcloud indisponible");
            }
        }
        return response;
    }

    static String buildSearchBody(String username, String rawScope, String query) {
        String scope = PathUtil.normalize(rawScope);
        String scopeHref = "/files/" + PathUtil.encodeSegment(username) + PathUtil.encodePath(scope);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<d:searchrequest xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">"
            + "<d:basicsearch><d:select><d:prop><oc:fileid/><d:displayname/><d:resourcetype/>"
            + "<d:getcontenttype/><d:getcontentlength/><d:creationdate/><d:getlastmodified/><d:getetag/>"
            + "<oc:size/><oc:permissions/></d:prop></d:select>"
            + "<d:from><d:scope><d:href>" + scopeHref + "</d:href><d:depth>infinity</d:depth></d:scope></d:from>"
            + "<d:where><d:like><d:prop><d:displayname/></d:prop><d:literal>%"
            + xmlText(query) + "%</d:literal></d:like></d:where>"
            + "<d:orderby><d:order><d:prop><d:displayname/></d:prop><d:ascending/></d:order></d:orderby>"
            + "<d:limit><d:nresults>500</d:nresults></d:limit></d:basicsearch></d:searchrequest>";
    }

    private static String xmlText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    HttpResponse<InputStream> get(String rawPath, String range) throws IOException, InterruptedException, HttpError {
        HttpRequest.Builder builder = request(PathUtil.normalize(rawPath)).GET();
        if (range != null && range.matches("bytes=\\d*-\\d*")) builder.header("Range", range);
        HttpResponse<InputStream> response = send(builder.build());
        if (response.statusCode() != 200 && response.statusCode() != 206) {
            try (InputStream body = response.body()) {
                throw statusError(response.statusCode(), "Impossible de télécharger le fichier Nextcloud", body);
            }
        }
        return response;
    }

    void putFile(String rawPath, Path source, String contentType) throws IOException, InterruptedException, HttpError {
        putFile(rawPath, source, contentType, true);
    }

    void putFile(String rawPath, Path source, String contentType, boolean overwrite)
        throws IOException, InterruptedException, HttpError {
        String path = PathUtil.normalize(rawPath);
        HttpRequest.Builder builder = request(path)
            .header("Content-Type", contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
        if (!overwrite) builder.header("If-None-Match", "*");
        HttpRequest request = builder.PUT(HttpRequest.BodyPublishers.ofFile(source)).build();
        expectEmpty(send(request), new int[]{201, 204}, "Impossible d’envoyer le fichier vers Nextcloud");
    }

    void putBytes(String rawPath, byte[] content, String contentType) throws IOException, InterruptedException, HttpError {
        putBytes(rawPath, content, contentType, true);
    }

    void putBytes(String rawPath, byte[] content, String contentType, boolean overwrite)
        throws IOException, InterruptedException, HttpError {
        String path = PathUtil.normalize(rawPath);
        HttpRequest.Builder builder = request(path)
            .header("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        if (!overwrite) builder.header("If-None-Match", "*");
        HttpRequest request = builder.PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build();
        expectEmpty(send(request), new int[]{201, 204}, "Impossible de créer le document dans Nextcloud");
    }

    void createDirectory(String rawPath) throws IOException, InterruptedException, HttpError {
        HttpRequest request = request(PathUtil.normalize(rawPath))
            .method("MKCOL", HttpRequest.BodyPublishers.noBody()).build();
        expectEmpty(send(request), new int[]{201}, "Impossible de créer le dossier Nextcloud");
    }

    Map<String, Object> startChunkedUpload(String rawPath, long totalBytes, String collisionPolicy,
        boolean createParents) throws IOException, InterruptedException, HttpError {
        if (totalBytes < 0L || totalBytes > config.maxUploadBytes) {
            throw new HttpError(413, "Le fichier dépasse la taille maximale autorisée");
        }
        String requested = PathUtil.normalize(rawPath);
        if (createParents) ensureParentDirectories(PathUtil.parent(requested));
        String policy = normalizeCollisionPolicy(collisionPolicy);
        String target;
        try { target = resolveCollision(requested, policy); }
        catch (HttpError e) {
            if (e.status != 208) throw e;
            LinkedHashMap<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("skipped", true);
            skipped.put("requestedPath", requested);
            skipped.put("targetPath", requested);
            skipped.put("chunkBytes", config.uploadChunkBytes);
            skipped.put("totalBytes", totalBytes);
            return skipped;
        }
        String uploadId = "zimbra-" + UUID.randomUUID();
        String uploadFolder = uploadBase() + "/" + uploadId;
        HttpRequest request = absoluteRequest(uploadFolder)
            .header("Destination", uri(target).toString())
            .method("MKCOL", HttpRequest.BodyPublishers.noBody()).build();
        expectEmpty(send(request), new int[]{201}, "Impossible de démarrer l’envoi découpé Nextcloud");

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("uploadId", uploadId);
        result.put("requestedPath", requested);
        result.put("targetPath", target);
        result.put("renamed", !requested.equals(target));
        result.put("replace", "replace".equals(policy));
        result.put("chunkBytes", config.uploadChunkBytes);
        result.put("totalBytes", totalBytes);
        return result;
    }

    Map<String, Object> putEmptyUpload(String rawPath, String collisionPolicy, boolean createParents)
        throws IOException, InterruptedException, HttpError {
        String requested = PathUtil.normalize(rawPath);
        if (createParents) ensureParentDirectories(PathUtil.parent(requested));
        String policy = normalizeCollisionPolicy(collisionPolicy);
        String target;
        try { target = resolveCollision(requested, policy); }
        catch (HttpError e) {
            if (e.status != 208) throw e;
            LinkedHashMap<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("skipped", true);
            skipped.put("requestedPath", requested);
            skipped.put("targetPath", requested);
            return skipped;
        }
        putBytes(target, new byte[0], "application/octet-stream", "replace".equals(policy));
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("skipped", false);
        result.put("requestedPath", requested);
        result.put("targetPath", target);
        result.put("renamed", !requested.equals(target));
        return result;
    }

    void putUploadChunk(String rawUploadId, int index, long totalBytes, byte[] content)
        throws IOException, InterruptedException, HttpError {
        String uploadId = validateUploadId(rawUploadId);
        if (index < 1 || index > 10000) throw new HttpError(400, "Numéro de morceau d’envoi invalide");
        if (content == null || content.length == 0 || content.length > config.uploadChunkBytes) {
            throw new HttpError(413, "Morceau d’envoi Nextcloud invalide");
        }
        if (totalBytes < content.length || totalBytes > config.maxUploadBytes) {
            throw new HttpError(413, "Taille totale d’envoi Nextcloud invalide");
        }
        String chunkName = String.format(Locale.ROOT, "%05d", index);
        HttpRequest request = absoluteRequest(uploadBase() + "/" + uploadId + "/" + chunkName)
            .header("OC-Total-Length", String.valueOf(totalBytes))
            .header("Content-Type", "application/octet-stream")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build();
        expectEmpty(send(request), new int[]{201, 204}, "Impossible d’envoyer un morceau vers Nextcloud");
    }

    void finishChunkedUpload(String rawUploadId, String rawTargetPath, long totalBytes, boolean replace)
        throws IOException, InterruptedException, HttpError {
        String uploadId = validateUploadId(rawUploadId);
        String target = PathUtil.normalize(rawTargetPath);
        if (totalBytes < 0L || totalBytes > config.maxUploadBytes) throw new HttpError(413, "Taille totale d’envoi invalide");
        HttpRequest request = absoluteRequest(uploadBase() + "/" + uploadId + "/.file")
            .header("Destination", uri(target).toString())
            .header("OC-Total-Length", String.valueOf(totalBytes))
            .header("Overwrite", replace ? "T" : "F")
            .method("MOVE", HttpRequest.BodyPublishers.noBody()).build();
        expectEmpty(send(request), new int[]{201, 204}, "Impossible d’assembler le fichier envoyé dans Nextcloud");
    }

    void cancelChunkedUpload(String rawUploadId) throws IOException, InterruptedException, HttpError {
        String uploadId = validateUploadId(rawUploadId);
        HttpResponse<InputStream> response = send(absoluteRequest(uploadBase() + "/" + uploadId).DELETE().build());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 204 && response.statusCode() != 404) {
                throw statusError(response.statusCode(), "Impossible d’annuler l’envoi Nextcloud", body);
            }
        }
    }

    HttpResponse<InputStream> getZip(String rawDirectory, List<String> rawNames)
        throws IOException, InterruptedException, HttpError {
        String directory = PathUtil.normalize(rawDirectory);
        if (rawNames == null || rawNames.isEmpty() || rawNames.size() > 200) {
            throw new HttpError(400, "Sélection ZIP Nextcloud invalide");
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String raw : rawNames) {
            String name = String.valueOf(raw);
            PathUtil.validateName(name);
            names.add(name);
        }
        // Nextcloud 31+ documents archive downloads through WebDAV query
        // parameters. This avoids repeated archive-selection headers being folded or
        // stripped by an intermediate reverse proxy.
        String endpoint = uri(directory, true) + "?accept=zip&files="
            + URLEncoder.encode(Json.stringify(new ArrayList<>(names)), StandardCharsets.UTF_8);
        HttpResponse<InputStream> response = send(absoluteRequest(endpoint)
            .setHeader("Accept", "application/zip").GET().build());
        if (response.statusCode() != 200) {
            try (InputStream body = response.body()) {
                throw statusError(response.statusCode(), "Impossible de créer l’archive ZIP Nextcloud", body);
            }
        }
        return response;
    }

    private void ensureParentDirectories(String rawDirectory) throws IOException, InterruptedException, HttpError {
        String directory = PathUtil.normalize(rawDirectory);
        if ("/".equals(directory)) return;
        String current = "/";
        for (String segment : directory.substring(1).split("/")) {
            current = PathUtil.join(current, segment);
            try {
                Map<String, Object> existing = stat(current);
                if (!Boolean.TRUE.equals(existing.get("isDirectory"))) {
                    throw new HttpError(409, "Un fichier empêche la création du dossier parent");
                }
                continue;
            } catch (HttpError e) {
                if (e.status != 404) throw e;
            }
            try {
                createDirectory(current);
            } catch (HttpError e) {
                // Un autre envoi peut avoir créé le dossier entre PROPFIND et MKCOL.
                // Nextcloud/SabreDAV répond selon les versions 405 ou 409 dans ce cas.
                if (e.status != 405 && e.status != 409) throw e;
                Map<String, Object> existing = stat(current);
                if (!Boolean.TRUE.equals(existing.get("isDirectory"))) {
                    throw new HttpError(409, "Un fichier empêche la création du dossier parent");
                }
            }
        }
    }

    String resolveCollision(String rawPath, String rawPolicy) throws IOException, InterruptedException, HttpError {
        String path = PathUtil.normalize(rawPath);
        String policy = normalizeCollisionPolicy(rawPolicy);
        boolean exists;
        try { stat(path); exists = true; }
        catch (HttpError e) {
            if (e.status == 404) exists = false;
            else throw e;
        }
        if (!exists || "replace".equals(policy)) return path;
        if ("skip".equals(policy)) throw new HttpError(208, "Ce fichier existe déjà et a été ignoré");
        if ("ask".equals(policy)) throw new HttpError(409, "Un élément portant ce nom existe déjà");
        String parent = PathUtil.parent(path);
        String name = PathUtil.fileName(path);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 1; index <= 1000; index++) {
            String candidate = PathUtil.join(parent, stem + " (" + index + ")" + extension);
            try { stat(candidate); }
            catch (HttpError e) {
                if (e.status == 404) return candidate;
                throw e;
            }
        }
        throw new HttpError(409, "Impossible de trouver un nom disponible dans ce dossier");
    }

    void delete(String rawPath) throws IOException, InterruptedException, HttpError {
        String path = PathUtil.normalize(rawPath);
        if ("/".equals(path)) throw new HttpError(400, "Le dossier racine ne peut pas être supprimé");
        HttpRequest request = request(path).DELETE().build();
        expectEmpty(send(request), new int[]{204}, "Impossible de supprimer l’élément Nextcloud");
    }

    void move(String rawFrom, String rawTo) throws IOException, InterruptedException, HttpError {
        move(rawFrom, rawTo, false);
    }

    void move(String rawFrom, String rawTo, boolean overwrite) throws IOException, InterruptedException, HttpError {
        String from = PathUtil.normalize(rawFrom);
        String to = PathUtil.normalize(rawTo);
        if ("/".equals(from) || "/".equals(to)) throw new HttpError(400, "Déplacement du dossier racine interdit");
        HttpRequest request = request(from)
            .header("Destination", uri(to).toString())
            .header("Overwrite", overwrite ? "T" : "F")
            .method("MOVE", HttpRequest.BodyPublishers.noBody())
            .build();
        expectEmpty(send(request), new int[]{201, 204}, "Impossible de déplacer ou renommer l’élément Nextcloud");
    }

    void copy(String rawFrom, String rawTo) throws IOException, InterruptedException, HttpError {
        copy(rawFrom, rawTo, false);
    }

    void copy(String rawFrom, String rawTo, boolean overwrite) throws IOException, InterruptedException, HttpError {
        String from = PathUtil.normalize(rawFrom);
        String to = PathUtil.normalize(rawTo);
        if ("/".equals(from) || "/".equals(to)) throw new HttpError(400, "Copie du dossier racine interdite");
        HttpRequest request = request(from)
            .header("Destination", uri(to).toString())
            .header("Depth", "infinity")
            .header("Overwrite", overwrite ? "T" : "F")
            .method("COPY", HttpRequest.BodyPublishers.noBody())
            .build();
        expectEmpty(send(request), new int[]{201, 204}, "Impossible de copier l’élément Nextcloud");
    }

    static String bulkDestination(String rawSource, String rawDirectory) {
        String source = PathUtil.normalize(rawSource);
        String directory = PathUtil.normalize(rawDirectory);
        if ("/".equals(source)) throw new IllegalArgumentException("Le dossier racine ne peut pas être traité");
        if (directory.equals(source) || directory.startsWith(source + "/")) {
            throw new IllegalArgumentException("La destination se trouve dans l’élément sélectionné");
        }
        String target = PathUtil.join(directory, PathUtil.fileName(source));
        if (target.equals(source)) throw new IllegalArgumentException("L’élément se trouve déjà dans ce dossier");
        return target;
    }

    Map<String, Object> officeConfig(String rawPath, String rawFileId, OfficeSettings office)
        throws IOException, InterruptedException, HttpError {
        String path = PathUtil.normalize(rawPath);
        String fileId = rawFileId == null ? "" : rawFileId.trim();
        if (!fileId.matches("[0-9]{1,20}")) {
            throw new HttpError(502, "Nextcloud n’a pas fourni l’identifiant nécessaire à la coédition");
        }
        String endpoint = baseUrl + "/ocs/v2.php/apps/" + office.connectorAppId + "/api/v1/config/"
            + PathUtil.encodeSegment(fileId)
            + "?format=json&inframe=true&filePath=" + PathUtil.encodeSegment(path);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(config.requestTimeout)
            .header("Authorization", basicAuth)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .header("User-Agent", Version.USER_AGENT)
            .GET().build();
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                if (response.statusCode() == 404) {
                    throw new HttpError(502,
                        "L’application " + office.connectorAppId + " de Nextcloud ne fournit pas son API de coédition");
                }
                throw statusError(response.statusCode(), "Impossible d’obtenir la session " + office.displayName + " de Nextcloud", body);
            }
            byte[] payload = body.readNBytes(MAX_OFFICE_CONFIG_BYTES + 1);
            if (payload.length > MAX_OFFICE_CONFIG_BYTES) {
                throw new HttpError(502, "Configuration " + office.displayName + " Nextcloud trop volumineuse");
            }
            try {
                Map<String, Object> result = unwrapOcsData(Json.parseObject(new String(payload, StandardCharsets.UTF_8)));
                String error = Json.string(result, "error", "").trim();
                if (!error.isEmpty()) throw new HttpError(502, "Nextcloud refuse l’ouverture " + office.displayName + " : " + error);
                boolean missingToken = office.jwtEnabled() && Json.string(result, "token", "").isBlank();
                if (!(result.get("document") instanceof Map) || !(result.get("editorConfig") instanceof Map) || missingToken) {
                    throw new HttpError(502, "Configuration " + office.displayName + " Nextcloud incomplète");
                }
                return result;
            } catch (HttpError e) {
                throw e;
            } catch (Exception e) {
                throw new HttpError(502, "Configuration " + office.displayName + " Nextcloud illisible", e);
            }
        }
    }

    static String buildAdvancedSearchBody(String username, String rawScope, String rawQuery, String rawCategory,
        String modifiedAfter, long minimumSize, long maximumSize, int limit) {
        String scope = PathUtil.normalize(rawScope);
        String query = rawQuery == null ? "" : rawQuery.trim();
        String category = rawCategory == null ? "all" : rawCategory.trim().toLowerCase(Locale.ROOT);
        String scopeHref = "/files/" + PathUtil.encodeSegment(username) + PathUtil.encodePath(scope);
        ArrayList<String> conditions = new ArrayList<>();
        if (!query.isEmpty()) {
            conditions.add("<d:like><d:prop><d:displayname/></d:prop><d:literal>%" + xmlText(query)
                + "%</d:literal></d:like>");
        }
        String mimePrefix = "image".equals(category) ? "image/" : ("video".equals(category) ? "video/"
            : ("audio".equals(category) ? "audio/" : ("text".equals(category) ? "text/" : "")));
        if (!mimePrefix.isEmpty()) {
            conditions.add("<d:like><d:prop><d:getcontenttype/></d:prop><d:literal>" + mimePrefix
                + "%</d:literal></d:like>");
        }
        if (modifiedAfter != null && !modifiedAfter.isBlank()) {
            conditions.add("<d:gte><d:prop><d:getlastmodified/></d:prop><d:literal>"
                + xmlText(modifiedAfter.trim()) + "</d:literal></d:gte>");
        }
        if (minimumSize >= 0L) {
            conditions.add("<d:gte><d:prop><d:getcontentlength/></d:prop><d:literal>" + minimumSize
                + "</d:literal></d:gte>");
        }
        if (maximumSize >= 0L) {
            conditions.add("<d:lte><d:prop><d:getcontentlength/></d:prop><d:literal>" + maximumSize
                + "</d:literal></d:lte>");
        }
        if ("office".equals(category)) {
            conditions.add("<d:or>"
                + mimeLike("application/vnd.openxmlformats-officedocument")
                + mimeLike("application/vnd.oasis.opendocument")
                + mimeLike("application/msword") + mimeLike("application/pdf") + "</d:or>");
        }
        String where;
        if (conditions.isEmpty()) where = "<d:is-defined><d:prop><d:getlastmodified/></d:prop></d:is-defined>";
        else if (conditions.size() == 1) where = conditions.get(0);
        else where = "<d:and>" + String.join("", conditions) + "</d:and>";

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<d:searchrequest xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\" xmlns:nc=\"http://nextcloud.org/ns\">"
            + "<d:basicsearch><d:select><d:prop><oc:fileid/><d:displayname/><d:resourcetype/>"
            + "<d:getcontenttype/><d:getcontentlength/><d:creationdate/><d:getlastmodified/><d:getetag/>"
            + "<oc:size/><oc:permissions/><oc:favorite/><oc:owner-id/><oc:owner-display-name/>"
            + "<oc:comments-count/><nc:has-preview/><nc:lock/><nc:lock-owner-displayname/>"
            + "</d:prop></d:select><d:from><d:scope><d:href>" + scopeHref + "</d:href>"
            + "<d:depth>infinity</d:depth></d:scope></d:from><d:where>" + where + "</d:where>"
            + "<d:orderby><d:order><d:prop><d:getlastmodified/></d:prop><d:descending/></d:order></d:orderby>"
            + "<d:limit><d:nresults>" + Math.max(1, Math.min(limit, 1000))
            + "</d:nresults></d:limit></d:basicsearch></d:searchrequest>";
    }

    private static String mimeLike(String prefix) {
        return "<d:like><d:prop><d:getcontenttype/></d:prop><d:literal>" + prefix + "%</d:literal></d:like>";
    }

    private String uploadBase() {
        return davRoot + "/uploads/" + PathUtil.encodeSegment(profile.username);
    }

    private static String validateUploadId(String rawUploadId) throws HttpError {
        String value = rawUploadId == null ? "" : rawUploadId.trim();
        if (!value.matches("zimbra-[0-9a-fA-F-]{36}")) throw new HttpError(400, "Identifiant d’envoi invalide");
        return value;
    }

    private static String normalizeCollisionPolicy(String rawPolicy) throws HttpError {
        String value = rawPolicy == null ? "ask" : rawPolicy.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("ask", "replace", "keep-both", "skip").contains(value)) {
            throw new HttpError(400, "Politique de conflit de fichier invalide");
        }
        return value;
    }

    private static String validateNumericId(String rawId, String label) throws HttpError {
        String value = rawId == null ? "" : rawId.trim();
        if (!value.matches("[0-9]{1,20}")) throw new HttpError(400, "Identifiant de " + label + " invalide");
        return value;
    }

    private static String limitText(String value, int maximum) throws HttpError {
        String text = value == null ? "" : value.trim();
        if (text.length() > maximum) throw new HttpError(400, "Texte Nextcloud trop long");
        return text;
    }

    private static String safeCssColor(String value) {
        String color = value == null ? "" : value.trim();
        return color.matches("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?") ? color : "#0082c9";
    }

    private Map<String, Object> ocsJson(HttpRequest request, String message)
        throws IOException, InterruptedException, HttpError {
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            byte[] payload = body.readNBytes(MAX_OFFICE_CONFIG_BYTES + 1);
            if (payload.length > MAX_OFFICE_CONFIG_BYTES) throw new HttpError(502, "Réponse OCS Nextcloud trop volumineuse");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw statusError(response.statusCode(), message, new ByteArrayInputStream(payload));
            }
            Map<String, Object> root;
            try { root = parseOcsJson(payload); }
            catch (IllegalArgumentException e) { throw new HttpError(502, "Réponse OCS Nextcloud illisible", e); }
            String status = ocsStatus(root);
            long code = ocsStatusCode(root);
            if (!status.isBlank() && !"ok".equalsIgnoreCase(status)) {
                String detail = ocsMessage(root);
                throw new HttpError(code == 404 ? 404 : (code == 403 ? 403 : 400),
                    detail.isBlank() ? message : detail);
            }
            return root;
        }
    }

    @SuppressWarnings("unchecked")
    static Object unwrapOcsValue(Map<String, Object> root) {
        Object ocs = root.get("ocs");
        if (ocs instanceof Map) return ((Map<String, Object>) ocs).get("data");
        return root.containsKey("data") ? root.get("data") : root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listValue(Object value) {
        if (value instanceof List) return (List<Object>) value;
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            for (String key : new String[]{"shares", "items", "data"}) {
                if (map.get(key) instanceof List) return (List<Object>) map.get(key);
            }
        }
        return Collections.emptyList();
    }

    private static List<Map<String, Object>> normalizeShares(Object value) {
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : listValue(value)) {
                Map<String, Object> map = mapValue(item);
                if (!map.isEmpty()) result.add(normalizeShare(map));
            }
        } else {
            Map<String, Object> map = mapValue(value);
            List<Object> nested = listValue(value);
            if (!nested.isEmpty()) {
                for (Object item : nested) {
                    Map<String, Object> child = mapValue(item);
                    if (!child.isEmpty()) result.add(normalizeShare(child));
                }
            } else if (!map.isEmpty()) result.add(normalizeShare(map));
        }
        return result;
    }

    private static Map<String, Object> normalizeShare(Map<String, Object> source) {
        LinkedHashMap<String, Object> share = new LinkedHashMap<>();
        share.put("id", stringAny(source, "id"));
        share.put("shareType", longAny(source, -1L, "share_type", "shareType"));
        share.put("shareWith", stringAny(source, "share_with", "shareWith"));
        share.put("shareWithDisplayName", stringAny(source, "share_with_displayname", "shareWithDisplayName"));
        share.put("uidOwner", stringAny(source, "uid_owner", "uidOwner"));
        share.put("displayNameOwner", stringAny(source, "displayname_owner", "displayNameOwner"));
        share.put("path", stringAny(source, "path", "file_target", "fileTarget"));
        share.put("itemType", stringAny(source, "item_type", "itemType"));
        share.put("mimeType", stringAny(source, "mimetype", "mimeType"));
        share.put("permissions", longAny(source, 1L, "permissions"));
        share.put("url", stringAny(source, "url"));
        share.put("token", stringAny(source, "token"));
        share.put("expiration", stringAny(source, "expiration", "expire_date"));
        share.put("note", stringAny(source, "note"));
        share.put("label", stringAny(source, "label"));
        share.put("passwordProtected", Boolean.TRUE.equals(source.get("password"))
            || !stringAny(source, "password").isBlank());
        return share;
    }

    private static void collectSharees(Object value, List<Map<String, Object>> result, Set<String> seen) {
        if (value instanceof List) {
            for (Object item : listValue(value)) collectSharees(item, result, seen);
            return;
        }
        Map<String, Object> source = mapValue(value);
        if (source.isEmpty()) return;
        Map<String, Object> nestedValue = mapValue(source.get("value"));
        String shareWith = stringAny(nestedValue, "shareWith", "share_with", "value");
        if (shareWith.isBlank()) shareWith = stringAny(source, "shareWith", "share_with", "value");
        long shareType = longAny(nestedValue, longAny(source, -1L, "shareType", "share_type"), "shareType", "share_type");
        String label = stringAny(source, "label", "displayName", "display_name");
        if (!shareWith.isBlank() && !label.isBlank()) {
            String identity = shareType + ":" + shareWith;
            if (seen.add(identity)) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("shareType", shareType);
                item.put("shareWith", shareWith);
                item.put("label", label);
                item.put("icon", stringAny(source, "icon"));
                result.add(item);
            }
            return;
        }
        for (Object child : source.values()) collectSharees(child, result, seen);
    }

    private static String stringAny(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private static long longAny(Map<String, Object> source, long fallback, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof Number) return ((Number) value).longValue();
            if (value != null) {
                try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException ignored) {}
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> unwrapOcsData(Map<String, Object> root) {
        Object ocs = root.get("ocs");
        if (ocs instanceof Map) {
            Object data = ((Map<String, Object>) ocs).get("data");
            if (data instanceof Map) return (Map<String, Object>) data;
        }
        Object data = root.get("data");
        if (data instanceof Map && !root.containsKey("document")) return (Map<String, Object>) data;
        return root;
    }

    private HttpRequest.Builder request(String path) {
        return request(path, false);
    }

    private HttpRequest.Builder request(String path, boolean collection) {
        return HttpRequest.newBuilder(uri(path, collection))
            .timeout(config.requestTimeout)
            .header("Authorization", basicAuth)
            .header("User-Agent", Version.USER_AGENT)
            .header("Accept", "*/*");
    }

    private HttpRequest.Builder absoluteRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(config.requestTimeout)
            .header("Authorization", basicAuth)
            .header("User-Agent", Version.USER_AGENT)
            .header("Accept", "*/*");
    }

    private String trashBase() {
        return davRoot + "/trashbin/" + PathUtil.encodeSegment(profile.username) + "/trash";
    }

    private static String validateTrashId(String rawTrashId) {
        String value = rawTrashId == null ? "" : rawTrashId.trim();
        PathUtil.validateName(value);
        return value;
    }

    private static void appendForm(StringBuilder form, String key, String value) {
        if (form.length() > 0) form.append('&');
        form.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
        form.append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static String ocsMessage(Map<String, Object> root) {
        Object ocs = root.get("ocs");
        if (!(ocs instanceof Map)) return "";
        Object meta = ((Map<String, Object>) ocs).get("meta");
        return meta instanceof Map ? Json.string((Map<String, Object>) meta, "message", "") : "";
    }

    @SuppressWarnings("unchecked")
    private static String ocsStatus(Map<String, Object> root) {
        Object ocs = root.get("ocs");
        if (!(ocs instanceof Map)) return "";
        Object meta = ((Map<String, Object>) ocs).get("meta");
        return meta instanceof Map ? Json.string((Map<String, Object>) meta, "status", "") : "";
    }

    @SuppressWarnings("unchecked")
    private static long ocsStatusCode(Map<String, Object> root) {
        Object ocs = root.get("ocs");
        if (!(ocs instanceof Map)) return -1L;
        Object meta = ((Map<String, Object>) ocs).get("meta");
        return meta instanceof Map ? Json.longValue((Map<String, Object>) meta, "statuscode", -1L) : -1L;
    }

    private static Map<String, Object> parseOcsJson(byte[] payload) {
        return Json.parseObject(new String(payload, StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return uri(path, false);
    }

    private URI uri(String path, boolean collection) {
        String encoded = PathUtil.encodePath(path);
        if (collection && !encoded.endsWith("/")) encoded += "/";
        return URI.create(davBase + encoded);
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        return transport.send(request);
    }

    private void expectEmpty(HttpResponse<InputStream> response, int[] expected, String message) throws IOException, HttpError {
        try (InputStream body = response.body()) {
            for (int status : expected) if (response.statusCode() == status) return;
            throw statusError(response.statusCode(), message, body);
        }
    }

    private static void expect(int actual, int expected, String message, InputStream body) throws IOException, HttpError {
        if (actual != expected) throw statusError(actual, message, body);
    }

    private static HttpError statusError(int status, String message, InputStream body) throws IOException {
        byte[] excerpt = body.readNBytes(8192);
        String detail = new String(excerpt, StandardCharsets.UTF_8).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (status == 401) return new HttpError(401, "Identifiant ou mot de passe d’application Nextcloud incorrect");
        if (status == 403) return new HttpError(403, "Nextcloud refuse cette opération");
        if (status == 404) return new HttpError(404, "Élément Nextcloud introuvable");
        if (status == 409 || status == 412) return new HttpError(409, "Un élément portant ce nom existe déjà ou le dossier parent est absent");
        if (!detail.isEmpty()) message += " (HTTP " + status + ")";
        return new HttpError(502, message + " (HTTP " + status + ")");
    }

    List<Map<String, Object>> parseMultistatus(InputStream input, String requestedPath) throws HttpError {
        return parseMultistatus(input, requestedPath, davBase);
    }

    List<Map<String, Object>> parseTrashMultistatus(InputStream input) throws HttpError {
        return parseMultistatus(input, "/", trashBase());
    }

    private List<Map<String, Object>> parseMultistatus(InputStream input, String requestedPath, String resourceBase) throws HttpError {
        try {
            byte[] xml = input.readNBytes(MAX_WEBDAV_XML_BYTES + 1);
            if (xml.length > MAX_WEBDAV_XML_BYTES) {
                throw new HttpError(502, "Réponse WebDAV Nextcloud trop volumineuse");
            }
            rejectDangerousXml(xml);
            Document document = secureDocumentBuilderFactory().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml));
            NodeList responses = document.getElementsByTagNameNS("*", "response");
            ArrayList<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < responses.getLength(); i++) {
                Element response = (Element) responses.item(i);
                Element prop = firstDescendant(response, "prop");
                if (prop == null) continue;
                String href = text(firstDescendant(response, "href"));
                String path = pathFromHref(href, i == 0 ? requestedPath : null, resourceBase);
                if (path == null) continue;
                String name = PathUtil.fileName(path);
                boolean directory = firstDescendant(firstDescendant(prop, "resourcetype"), "collection") != null;
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("name", "/".equals(path) ? "Mes fichiers" : name);
                item.put("path", path);
                item.put("isDirectory", directory);
                item.put("mimeType", directory ? "httpd/unix-directory" : text(firstDescendant(prop, "getcontenttype")));
                item.put("size", parseLong(text(firstDescendant(prop, "size")), parseLong(text(firstDescendant(prop, "getcontentlength")), 0)));
                item.put("created", normalizeDate(text(firstDescendant(prop, "creationdate"))));
                item.put("modified", normalizeDate(text(firstDescendant(prop, "getlastmodified"))));
                item.put("quotaUsed", parseLong(text(firstDescendant(prop, "quota-used-bytes")), -1L));
                item.put("quotaAvailable", parseLong(text(firstDescendant(prop, "quota-available-bytes")), -2L));
                item.put("etag", trimQuotes(text(firstDescendant(prop, "getetag"))));
                item.put("fileId", text(firstDescendant(prop, "fileid")));
                String permissions = text(firstDescendant(prop, "permissions"));
                item.put("permissions", permissions);
                item.put("favorite", "1".equals(text(firstDescendant(prop, "favorite"))));
                item.put("commentsCount", parseLong(text(firstDescendant(prop, "comments-count")), 0L));
                item.put("commentsUnread", parseLong(text(firstDescendant(prop, "comments-unread")), 0L));
                item.put("ownerId", text(firstDescendant(prop, "owner-id")));
                item.put("ownerDisplayName", text(firstDescendant(prop, "owner-display-name")));
                item.put("checksums", descendantTexts(firstDescendant(prop, "checksums"), "checksum"));
                item.put("tags", descendantTexts(firstDescendant(prop, "tags"), "tag"));
                item.put("shareTypes", descendantLongs(firstDescendant(prop, "share-types"), "share-type"));
                item.put("hasPreview", "true".equalsIgnoreCase(text(firstDescendant(prop, "has-preview"))));
                item.put("mountType", text(firstDescendant(prop, "mount-type")));
                item.put("hideDownload", "true".equalsIgnoreCase(text(firstDescendant(prop, "hide-download"))));
                item.put("mountRoot", "true".equalsIgnoreCase(text(firstDescendant(prop, "is-mount-root"))));
                item.put("containedFolderCount", parseLong(text(firstDescendant(prop, "contained-folder-count")), -1L));
                item.put("containedFileCount", parseLong(text(firstDescendant(prop, "contained-file-count")), -1L));
                item.put("uploaded", normalizeEpochDate(text(firstDescendant(prop, "upload_time"))));
                boolean locked = "1".equals(text(firstDescendant(prop, "lock")));
                String lockOwner = text(firstDescendant(prop, "lock-owner"));
                item.put("locked", locked);
                item.put("lockOwner", lockOwner);
                item.put("lockOwnerDisplayName", text(firstDescendant(prop, "lock-owner-displayname")));
                item.put("lockOwnerEditor", text(firstDescendant(prop, "lock-owner-editor")));
                item.put("lockTime", normalizeEpochDate(text(firstDescendant(prop, "lock-time"))));
                item.put("lockTimeout", parseLong(text(firstDescendant(prop, "lock-timeout")), 0L));
                item.put("sharePermissions", parseLong(text(firstDescendant(prop, "share-permissions")), -1L));
                applyPermissionFlags(item, permissions, directory, locked && !profile.username.equals(lockOwner));
                String trashFilename = text(firstDescendant(prop, "trashbin-filename"));
                String trashOriginalLocation = text(firstDescendant(prop, "trashbin-original-location"));
                String trashDeletionTime = normalizeEpochDate(text(firstDescendant(prop, "trashbin-deletion-time")));
                item.put("trashId", PathUtil.fileName(path));
                item.put("trashFilename", trashFilename);
                item.put("trashOriginalLocation", trashOriginalLocation);
                item.put("trashDeletionTime", trashDeletionTime);
                if (!trashFilename.isBlank()) item.put("name", trashFilename);
                result.add(item);
            }
            return result;
        } catch (HttpError e) {
            throw e;
        } catch (Exception e) {
            throw new HttpError(502, "Réponse WebDAV Nextcloud illisible (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private List<VersionResource> parseVersionResources(InputStream input, String versionsBase) throws HttpError {
        try {
            byte[] xml = input.readNBytes(MAX_WEBDAV_XML_BYTES + 1);
            if (xml.length > MAX_WEBDAV_XML_BYTES) throw new HttpError(502, "Réponse des versions Nextcloud trop volumineuse");
            rejectDangerousXml(xml);
            Document document = secureDocumentBuilderFactory().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList responses = document.getElementsByTagNameNS("*", "response");
            ArrayList<VersionResource> result = new ArrayList<>();
            String basePath = URI.create(versionsBase).getRawPath();
            for (int i = 0; i < responses.getLength(); i++) {
                Element response = (Element) responses.item(i);
                Element prop = firstDescendant(response, "prop");
                String href = text(firstDescendant(response, "href"));
                if (prop == null || href.isBlank()) continue;
                String rawPath = URI.create(href).getRawPath();
                if (!rawPath.startsWith(basePath + "/")) continue;
                String child = rawPath.substring(basePath.length() + 1);
                if (child.isBlank() || child.indexOf('/') >= 0) continue;
                String versionId = URLDecoder.decode(child, StandardCharsets.UTF_8);
                if (!versionId.matches("[0-9]{1,20}")) continue;
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("versionId", versionId);
                item.put("size", parseLong(text(firstDescendant(prop, "getcontentlength")), 0L));
                item.put("modified", normalizeDate(text(firstDescendant(prop, "getlastmodified"))));
                item.put("etag", trimQuotes(text(firstDescendant(prop, "getetag"))));
                item.put("label", text(firstDescendant(prop, "version-label")));
                item.put("author", text(firstDescendant(prop, "version-author")));
                try { item.put("timestamp", Instant.ofEpochSecond(Long.parseLong(versionId)).toString()); }
                catch (RuntimeException e) { item.put("timestamp", item.get("modified")); }
                result.add(new VersionResource(versionId, sameOriginUri(rawPath), item));
            }
            result.sort(Comparator.comparing(resource -> String.valueOf(resource.details.get("timestamp")), Comparator.reverseOrder()));
            return result;
        } catch (HttpError e) {
            throw e;
        } catch (Exception e) {
            throw new HttpError(502, "Réponse des versions Nextcloud illisible", e);
        }
    }

    private URI sameOriginUri(String rawPath) throws HttpError {
        URI base = URI.create(baseUrl);
        if (rawPath == null || !rawPath.startsWith("/") || rawPath.indexOf('\r') >= 0 || rawPath.indexOf('\n') >= 0) {
            throw new HttpError(502, "Erreur interne de la connexion Nextcloud");
        }
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + rawPath);
    }

    private static final class VersionResource {
        final String versionId;
        final URI uri;
        final Map<String, Object> details;

        VersionResource(String versionId, URI uri, Map<String, Object> details) {
            this.versionId = versionId;
            this.uri = uri;
            this.details = details;
        }
    }

    private List<Map<String, Object>> parseComments(InputStream input) throws HttpError {
        try {
            byte[] xml = input.readNBytes(MAX_WEBDAV_XML_BYTES + 1);
            if (xml.length > MAX_WEBDAV_XML_BYTES) throw new HttpError(502, "Réponse des commentaires Nextcloud trop volumineuse");
            rejectDangerousXml(xml);
            Document document = secureDocumentBuilderFactory().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList responses = document.getElementsByTagNameNS("*", "response");
            ArrayList<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < responses.getLength(); i++) {
                Element response = (Element) responses.item(i);
                Element container = firstDescendant(response, "comment");
                if (container == null) container = firstDescendant(response, "prop");
                if (container == null) continue;
                String id = text(firstDescendant(container, "id"));
                String message = text(firstDescendant(container, "message"));
                if (!id.matches("[0-9]{1,20}") || message.isBlank()) continue;
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("parentId", text(firstDescendant(container, "parentId")));
                item.put("message", message);
                item.put("verb", text(firstDescendant(container, "verb")));
                item.put("actorId", text(firstDescendant(container, "actorId")));
                String displayName = text(firstDescendant(container, "actorDisplayName"));
                item.put("actorDisplayName", displayName.isBlank() ? item.get("actorId") : displayName);
                item.put("created", normalizeDate(text(firstDescendant(container, "creationDateTime"))));
                result.add(item);
            }
            result.sort(Comparator.comparing(item -> String.valueOf(item.get("created"))));
            return result;
        } catch (HttpError e) {
            throw e;
        } catch (Exception e) {
            throw new HttpError(502, "Réponse des commentaires Nextcloud illisible", e);
        }
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        try { factory.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) {}
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
        catch (Exception ignored) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); }
        catch (Exception ignored) {}
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
        catch (Exception ignored) {}
        try { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); }
        catch (IllegalArgumentException ignored) {}
        try { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); }
        catch (IllegalArgumentException ignored) {}
        return factory;
    }

    private static void rejectDangerousXml(byte[] xml) throws HttpError {
        String markup = new String(xml, StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        if (markup.contains("<!DOCTYPE") || markup.contains("<!ENTITY")) {
            throw new HttpError(502, "Réponse WebDAV Nextcloud interdite");
        }
    }

    private static Element firstDescendant(Element root, String localName) {
        if (root == null) return null;
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static List<String> descendantTexts(Element root, String localName) {
        if (root == null) return new ArrayList<>();
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = nodes.item(i).getTextContent().trim();
            if (!value.isBlank()) result.add(value);
        }
        return result;
    }

    private static List<Long> descendantLongs(Element root, String localName) {
        ArrayList<Long> result = new ArrayList<>();
        for (String value : descendantTexts(root, localName)) {
            try { result.add(Long.parseLong(value)); } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private static void applyPermissionFlags(Map<String, Object> item, String permissions, boolean directory,
        boolean lockedByOther) {
        boolean known = permissions != null && !permissions.isBlank();
        boolean readable = !known || permissions.indexOf('G') >= 0 || permissions.indexOf('W') >= 0;
        boolean writable = (!known || permissions.indexOf('W') >= 0) && !lockedByOther;
        boolean mountRoot = Boolean.TRUE.equals(item.get("mountRoot"));
        boolean hideDownload = Boolean.TRUE.equals(item.get("hideDownload"));
        item.put("canRead", readable);
        item.put("canDownload", readable && !hideDownload);
        item.put("canWrite", writable);
        item.put("canEdit", !directory && writable);
        item.put("canCreateFile", directory && !lockedByOther && (!known || permissions.indexOf('C') >= 0));
        item.put("canCreateFolder", directory && !lockedByOther && (!known || permissions.indexOf('K') >= 0));
        item.put("canDelete", !mountRoot && !lockedByOther && (!known || permissions.indexOf('D') >= 0));
        item.put("canRename", !mountRoot && !lockedByOther && (!known || permissions.indexOf('N') >= 0));
        item.put("canMove", !mountRoot && !lockedByOther && (!known || permissions.indexOf('V') >= 0));
        item.put("canCopy", readable);
        item.put("canShare", !known || permissions.indexOf('R') >= 0);
        item.put("lockedByOther", lockedByOther);
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().trim();
    }

    private String pathFromHref(String href, String fallback, String resourceBase) {
        if (href == null || href.isBlank()) return fallback;
        try {
            String rawPath = URI.create(href).getRawPath();
            String rawBase = URI.create(resourceBase).getRawPath();
            if (!rawPath.equals(rawBase) && !rawPath.startsWith(rawBase + "/")) return fallback;
            String relative = rawPath.substring(rawBase.length());
            String result = "/";
            for (String segment : relative.split("/")) {
                if (segment.isBlank()) continue;
                String decoded = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
                result = PathUtil.join(result, decoded);
            }
            return PathUtil.normalize(result);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try { return value == null || value.isBlank() ? fallback : Long.parseLong(value); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        return parseLong(value == null ? "" : String.valueOf(value), fallback);
    }

    private static String trimQuotes(String value) {
        if (value == null) return "";
        return value.replaceFirst("^W/", "").replaceAll("^\"|\"$", "");
    }

    private static String normalizeDate(String value) {
        if (value == null || value.isBlank()) return "";
        try { return meaningfulDate(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()); }
        catch (DateTimeParseException ignored) {
            try { return meaningfulDate(Instant.parse(value)); } catch (DateTimeParseException ignoredAgain) { return value; }
        }
    }

    private static String normalizeEpochDate(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.matches("[0-9]{1,12}")) {
            try { return meaningfulDate(Instant.ofEpochSecond(Long.parseLong(value))); }
            catch (RuntimeException ignored) {}
        }
        return normalizeDate(value);
    }

    private static String meaningfulDate(Instant instant) {
        return instant == null || instant.getEpochSecond() <= 86_400L ? "" : instant.toString();
    }
}
