package fr.franckchalon.zimbra.nextcloud;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class OnlyOfficeService {
    private static final Set<String> WORD = Set.of("doc", "docx", "odt", "rtf", "txt", "html", "htm", "epub", "fb2");
    private static final Set<String> CELL = Set.of("xls", "xlsx", "ods", "csv");
    private static final Set<String> SLIDE = Set.of("ppt", "pptx", "odp");
    private static final Set<String> PDF = Set.of("pdf");

    private final AppConfig config;
    private final CredentialStore store;
    private final TicketService tickets;
    private final HttpClient http;

    OnlyOfficeService(AppConfig config, CredentialStore store, TicketService tickets) {
        this.config = config;
        this.store = store;
        this.tickets = tickets;
        this.http = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    String editorHtml(AccountContext account, Profile profile, String rawPath, String requestedLanguage)
        throws IOException, InterruptedException, HttpError, GeneralSecurityException {
        String path = PathUtil.normalize(rawPath);
        OfficeSettings office = profile.office.resolve(config);
        NextcloudClient nextcloud = new NextcloudClient(config, profile);
        Map<String, Object> metadata = nextcloud.stat(path);
        if (Boolean.TRUE.equals(metadata.get("isDirectory"))) throw new HttpError(400, "Un dossier ne peut pas être ouvert dans " + office.displayName);

        String extension = PathUtil.extension(path);
        String documentType = documentType(extension);
        if (documentType == null) throw new HttpError(400, "Ce format de fichier n’est pas pris en charge par " + office.displayName);

        String fileId = String.valueOf(metadata.getOrDefault("fileId", ""));
        Map<String, Object> nextcloudEditor = nextcloud.officeConfig(path, fileId, office);
        if (office.jwtEnabled()) {
            try {
                Jwt.verify(Json.string(nextcloudEditor, "token", ""), office.jwtSecret);
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new HttpError(502,
                    "Le jeton JWT " + office.displayName + " fourni par Nextcloud ne correspond pas au secret configuré pour ce compte Cloud",
                    e);
            }
        }
        String nextcloudOfficeUrl;
        try {
            nextcloudOfficeUrl = config.validateOfficeEditorUrl(
                Json.string(nextcloudEditor, "documentServerUrl", office.publicUrl), office
            );
        } catch (IllegalArgumentException e) {
            throw new HttpError(502, e.getMessage(), e);
        }
        applyEditorLocale(nextcloudEditor, requestedLanguage);
        if (office.jwtEnabled()) {
            LinkedHashMap<String, Object> localizedPayload = new LinkedHashMap<>(nextcloudEditor);
            localizedPayload.remove("token");
            nextcloudEditor.put("token", Jwt.sign(localizedPayload, office.jwtSecret));
        }
        return buildHtml(nextcloudEditor, nextcloudOfficeUrl, office.displayName);
    }

    String legacyEditorHtml(AccountContext account, Profile profile, String rawPath)
        throws IOException, InterruptedException, HttpError, GeneralSecurityException {
        String path = PathUtil.normalize(rawPath);
        OfficeSettings office = profile.office.resolve(config);
        NextcloudClient nextcloud = new NextcloudClient(config, profile);
        Map<String, Object> metadata = nextcloud.stat(path);
        if (Boolean.TRUE.equals(metadata.get("isDirectory"))) throw new HttpError(400, "Un dossier ne peut pas être ouvert dans " + office.displayName);

        String extension = PathUtil.extension(path);
        String documentType = documentType(extension);
        if (documentType == null) throw new HttpError(400, "Ce format de fichier n’est pas pris en charge par " + office.displayName);

        String contentTicket = tickets.create("content", account.id, profile.id, path);
        String callbackTicket = tickets.create("callback", account.id, profile.id, path);
        String contentUrl = config.zimbraPublicUrl + "/service/extension/nextcloud-connector/public/content?ticket=" + contentTicket;
        String callbackUrl = config.zimbraPublicUrl + "/service/extension/nextcloud-connector/public/callback?ticket=" + callbackTicket;
        String keySeed = account.id + "\n" + path + "\n" + metadata.getOrDefault("etag", metadata.getOrDefault("modified", ""));
        String documentKey = Crypto.sha256Hex(keySeed).substring(0, 48);

        LinkedHashMap<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("edit", true);
        permissions.put("download", true);
        permissions.put("print", true);
        permissions.put("review", true);
        permissions.put("comment", true);

        LinkedHashMap<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", extension);
        document.put("key", documentKey);
        document.put("title", PathUtil.fileName(path));
        document.put("url", contentUrl);
        document.put("permissions", permissions);

        LinkedHashMap<String, Object> user = new LinkedHashMap<>();
        user.put("id", Crypto.sha256Hex(account.id).substring(0, 24));
        user.put("name", account.email);

        LinkedHashMap<String, Object> customization = new LinkedHashMap<>();
        customization.put("compactToolbar", true);
        customization.put("autosave", true);
        customization.put("forcesave", true);
        customization.put("help", true);

        LinkedHashMap<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("callbackUrl", callbackUrl);
        EditorLocale locale = EditorLocale.from(ServerI18n.locale());
        editorConfig.put("lang", locale.editorLanguage);
        editorConfig.put("region", locale.editorRegion);
        editorConfig.put("mode", "edit");
        editorConfig.put("user", user);
        editorConfig.put("customization", customization);

        LinkedHashMap<String, Object> editor = new LinkedHashMap<>();
        editor.put("type", "desktop");
        editor.put("width", "100%");
        editor.put("height", "100%");
        editor.put("documentType", documentType);
        editor.put("document", document);
        editor.put("editorConfig", editorConfig);

        LinkedHashMap<String, Object> jwtPayload = new LinkedHashMap<>(editor);
        jwtPayload.put("exp", Instant.now().getEpochSecond() + 3600L);
        if (office.jwtEnabled()) editor.put("token", Jwt.sign(jwtPayload, office.jwtSecret));
        return buildHtml(editor, office.publicUrl, office.displayName);
    }

    void processCallback(TicketService.Ticket ticket, Profile profile, OfficeSettings office,
            Map<String, Object> callback, String authorization)
        throws IOException, InterruptedException, HttpError, GeneralSecurityException {
        long status = Json.longValue(callback, "status", -1);
        if (status != 2 && status != 6) return;
        String token = bearerToken(authorization);
        if (token == null || token.isBlank()) token = Json.string(callback, "token", "");
        if (office.jwtEnabled()) Jwt.verify(token, office.jwtSecret);
        String downloadUrl = Json.string(callback, "url", "");
        if (downloadUrl.isBlank()) throw new HttpError(400, office.displayName + " n’a pas transmis l’adresse du document modifié");
        URI validated = config.validateOfficeDownloadUrl(downloadUrl, office);
        Path temporary = Files.createTempFile(store.tempDirectory(), "office-save-", ".tmp");
        try {
            HttpRequest request = HttpRequest.newBuilder(validated)
                .timeout(config.requestTimeout)
                .header("User-Agent", Version.USER_AGENT)
                .GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream ignored = response.body()) {}
                throw new HttpError(502, office.displayName + " n’a pas permis de télécharger la version modifiée (HTTP " + response.statusCode() + ")");
            }
            try (InputStream input = response.body()) {
                copyLimited(input, temporary, config.maxOfficeDownloadBytes);
            }
            new NextcloudClient(config, profile).putFile(ticket.path, temporary, contentTypeFor(ticket.path));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void copyLimited(InputStream input, Path destination, long maximum) throws IOException, HttpError {
        long written = 0;
        byte[] buffer = new byte[64 * 1024];
        try (java.io.OutputStream output = Files.newOutputStream(destination)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                written += count;
                if (written > maximum) throw new HttpError(413, "Le fichier dépasse la taille maximale autorisée");
                output.write(buffer, 0, count);
            }
        }
    }

    private String buildHtml(Map<String, Object> editor, String officeUrl, String officeDisplayName) {
        String apiUrl = officeUrl + "/web-apps/apps/api/documents/api.js";
        URI office = URI.create(officeUrl);
        String origin = office.getScheme() + "://" + office.getAuthority();
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Crypto.randomBytes(18));
        String editorJson = Json.stringify(editor).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        String csp = "default-src 'none'; script-src 'nonce-" + nonce + "' " + origin
            + "; style-src 'nonce-" + nonce + "'; frame-src " + origin + "; connect-src " + origin
            + "; img-src " + origin + " data:; frame-ancestors 'self'";

        String localizedEditorError = Json.stringify(ServerI18n.text("editorFailed"));
        return "<!doctype html><html lang=\"" + htmlAttribute(ServerI18n.language()) + "\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + htmlAttribute(csp) + "\">"
            + "<title>" + htmlAttribute(officeDisplayName) + "</title><style nonce=\"" + nonce + "\">"
            + "html,body,#editor{width:100%;height:100%;margin:0;overflow:hidden;background:#fff}"
            + "#error{display:none;padding:24px;font:14px sans-serif;color:#a61b10;background:#fff2f0}"
            + "</style></head><body><div id=\"editor\"></div><div id=\"error\"></div>"
            + "<script nonce=\"" + nonce + "\" src=\"" + htmlAttribute(apiUrl) + "\"></script>"
            + "<script nonce=\"" + nonce + "\">(function(){try{if(!window.DocsAPI)throw new Error(" + localizedEditorError + ");"
            + "window.nextcloudEditor=new window.DocsAPI.DocEditor('editor'," + editorJson + ");}catch(e){"
            + "var box=document.getElementById('error');box.style.display='block';box.textContent=" + localizedEditorError + "+' : '+e.message;"
            + "document.getElementById('editor').style.display='none';}}());</script></body></html>";
    }

    @SuppressWarnings("unchecked")
    static void applyEditorLocale(Map<String, Object> editor, String requestedLanguage) {
        EditorLocale locale = EditorLocale.from(requestedLanguage);
        Object rawEditorConfig = editor.get("editorConfig");
        Map<String, Object> editorConfig;
        if (rawEditorConfig instanceof Map) editorConfig = (Map<String, Object>) rawEditorConfig;
        else {
            editorConfig = new LinkedHashMap<>();
            editor.put("editorConfig", editorConfig);
        }
        editorConfig.put("lang", locale.editorLanguage);
        editorConfig.put("region", locale.editorRegion);
    }

    String contentTypeFor(String path) {
        switch (PathUtil.extension(path)) {
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "odt": return "application/vnd.oasis.opendocument.text";
            case "rtf": return "application/rtf";
            case "txt": return "text/plain; charset=UTF-8";
            case "html": case "htm": return "text/html; charset=UTF-8";
            case "epub": return "application/epub+zip";
            case "fb2": return "application/x-fictionbook+xml";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
            case "csv": return "text/csv; charset=UTF-8";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "odp": return "application/vnd.oasis.opendocument.presentation";
            case "pdf": return "application/pdf";
            default: return "application/octet-stream";
        }
    }

    private static String documentType(String extension) {
        String ext = extension.toLowerCase(Locale.ROOT);
        if (WORD.contains(ext)) return "word";
        if (CELL.contains(ext)) return "cell";
        if (SLIDE.contains(ext)) return "slide";
        if (PDF.contains(ext)) return "pdf";
        return null;
    }

    private static String bearerToken(String authorization) {
        if (authorization == null) return null;
        int space = authorization.indexOf(' ');
        if (space > 0 && "bearer".equalsIgnoreCase(authorization.substring(0, space))) return authorization.substring(space + 1).trim();
        return authorization.trim();
    }

    private static String htmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
