package fr.franckchalon.zimbra.nextcloud;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class NextcloudProvisioningService {
    private static final int MAX_OCS_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final String USER_AGENT = Version.USER_AGENT;

    private final AppConfig config;
    private final CredentialStore store;
    private final ProvisioningTransport transport;
    private final ProfileVerifier verifier;

    NextcloudProvisioningService(AppConfig config, CredentialStore store) {
        this(config, store, new JavaHttpTransport(config), profile -> new NextcloudClient(config, profile).list("/"));
    }

    NextcloudProvisioningService(AppConfig config, CredentialStore store,
                                 ProvisioningTransport transport, ProfileVerifier verifier) {
        this.config = config;
        this.store = store;
        this.transport = transport;
        this.verifier = verifier;
    }

    synchronized ActivationResult activate(AccountContext account)
        throws IOException, InterruptedException, HttpError, GeneralSecurityException {
        if (!config.managedAccountsEnabled()) {
            throw new HttpError(409, "Le provisionnement automatique Nextcloud n’est pas activé par l’administrateur");
        }
        if (store.load(account.id).isPresent()) {
            throw new HttpError(409, "Ce compte Zimbra possède déjà une connexion Nextcloud");
        }

        String userId = managedUserId(account.email);
        String initialPassword = generateInitialPassword();
        boolean created = false;
        try {
            createUser(userId, account.email, initialPassword);
            created = true;
            String appPassword = createAppPassword(userId, initialPassword);
            Profile base = new Profile(config.managedNextcloudUrl, userId, appPassword, Instant.now().getEpochSecond());
            Profile candidate = new Profile(base.id, "", config.managedNextcloudUrl, userId, appPassword,
                Instant.now().getEpochSecond(), true);
            verifier.verify(candidate);
            store.save(account.id, candidate);
            return new ActivationResult(userId, initialPassword, config.managedNextcloudUrl, config.managedQuota, candidate);
        } catch (IOException | InterruptedException | HttpError | GeneralSecurityException e) {
            if (created) deleteCreatedUserQuietly(userId);
            throw e;
        } catch (RuntimeException e) {
            if (created) deleteCreatedUserQuietly(userId);
            throw e;
        }
    }

    private void createUser(String userId, String email, String initialPassword)
        throws IOException, InterruptedException, HttpError {
        String form = createUserForm(userId, email, initialPassword,
            config.managedGroup, config.managedQuota, config.managedLanguage);
        OcsResponse response = sendOcs(HttpRequest.newBuilder(
                URI.create(config.managedNextcloudUrl + "/ocs/v1.php/cloud/users?format=json"))
            .timeout(config.requestTimeout)
            .header("Authorization", adminAuthorization())
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build());
        if (response.code == 102) {
            throw new HttpError(409,
                "Un compte Nextcloud existe déjà avec l’identifiant " + userId
                    + ". Son mot de passe ne sera pas réinitialisé automatiquement par sécurité.");
        }
        requireSuccess(response, "Nextcloud n’a pas pu créer le compte " + userId);
    }

    private String createAppPassword(String userId, String initialPassword)
        throws IOException, InterruptedException, HttpError {
        OcsResponse response = sendOcs(HttpRequest.newBuilder(
                URI.create(config.managedNextcloudUrl + "/ocs/v2.php/core/getapppassword?format=json"))
            .timeout(config.requestTimeout)
            .header("Authorization", basicAuthorization(userId, initialPassword))
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET().build());
        requireSuccess(response, "Nextcloud n’a pas pu générer le mot de passe d’application de la Zimlet");
        String appPassword = Json.string(response.data, "apppassword", "").trim();
        if (appPassword.isBlank()) {
            throw new HttpError(502, "Nextcloud n’a pas renvoyé le mot de passe d’application attendu");
        }
        return appPassword;
    }

    private void deleteCreatedUserQuietly(String userId) {
        try {
            sendOcs(HttpRequest.newBuilder(URI.create(
                    config.managedNextcloudUrl + "/ocs/v1.php/cloud/users/" + PathUtil.encodeSegment(userId) + "?format=json"))
                .timeout(config.requestTimeout)
                .header("Authorization", adminAuthorization())
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .DELETE().build());
        } catch (Exception ignored) {}
    }

    private OcsResponse sendOcs(HttpRequest request) throws IOException, InterruptedException, HttpError {
        TransportResponse response = transport.send(request);
        byte[] payload;
        try (InputStream body = response.body) {
            payload = body.readNBytes(MAX_OCS_RESPONSE_BYTES + 1);
        }
        if (payload.length > MAX_OCS_RESPONSE_BYTES) {
            throw new HttpError(502, "Réponse de l’API de provisionnement Nextcloud trop volumineuse");
        }
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new HttpError(502, "API de provisionnement Nextcloud indisponible (HTTP " + response.statusCode + ")");
        }
        try {
            Map<String, Object> root = Json.parseObject(new String(payload, StandardCharsets.UTF_8));
            Object ocsObject = root.get("ocs");
            if (!(ocsObject instanceof Map)) throw new IllegalArgumentException("Enveloppe OCS absente");
            @SuppressWarnings("unchecked")
            Map<String, Object> ocs = (Map<String, Object>) ocsObject;
            Object metaObject = ocs.get("meta");
            Object dataObject = ocs.get("data");
            if (!(metaObject instanceof Map)) throw new IllegalArgumentException("Métadonnées OCS absentes");
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) metaObject;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = dataObject instanceof Map
                ? (Map<String, Object>) dataObject : new LinkedHashMap<>();
            return new OcsResponse(
                Json.longValue(meta, "statuscode", -1),
                Json.string(meta, "status", ""),
                Json.string(meta, "message", ""),
                data
            );
        } catch (IllegalArgumentException e) {
            throw new HttpError(502, "Réponse de l’API de provisionnement Nextcloud illisible", e);
        }
    }

    private static void requireSuccess(OcsResponse response, String prefix) throws HttpError {
        if ((response.code == 100 || response.code == 200) && "ok".equalsIgnoreCase(response.status)) return;
        String detail = response.message.isBlank() ? "code OCS " + response.code : response.message;
        throw new HttpError(502, prefix + " : " + detail);
    }

    private String adminAuthorization() {
        return basicAuthorization(config.managedAdminUsername, config.managedAdminAppPassword);
    }

    private static String basicAuthorization(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
            (username + ":" + password).getBytes(StandardCharsets.UTF_8)
        );
    }

    static String managedUserId(String email) {
        String userId = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (userId.isBlank() || userId.length() > 320 || !userId.contains("@")) {
            throw new IllegalArgumentException("L’adresse du compte Zimbra ne peut pas servir d’identifiant Nextcloud");
        }
        PathUtil.validateName(userId);
        return userId;
    }

    static String generateInitialPassword() {
        return "Nc!7-" + Base64.getUrlEncoder().withoutPadding().encodeToString(Crypto.randomBytes(32));
    }

    static String createUserForm(String userId, String email, String password,
                                 String group, String quota, String language) {
        StringBuilder form = new StringBuilder();
        appendForm(form, "userid", userId);
        appendForm(form, "password", password);
        appendForm(form, "displayName", email);
        appendForm(form, "email", email);
        if (group != null && !group.isBlank()) appendForm(form, "groups[]", group);
        if (quota != null && !quota.isBlank()) appendForm(form, "quota", quota);
        if (language != null && !language.isBlank()) appendForm(form, "language", language);
        return form.toString();
    }

    private static void appendForm(StringBuilder form, String key, String value) {
        if (form.length() > 0) form.append('&');
        form.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
        form.append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    static final class ActivationResult {
        final String username;
        final String initialPassword;
        final String nextcloudUrl;
        final String quota;
        final Profile profile;

        ActivationResult(String username, String initialPassword, String nextcloudUrl, String quota, Profile profile) {
            this.username = username;
            this.initialPassword = initialPassword;
            this.nextcloudUrl = nextcloudUrl;
            this.quota = quota;
            this.profile = profile;
        }

        Map<String, Object> toMap(Map<String, Object> profile) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("profile", profile);
            result.put("username", username);
            result.put("initialPassword", initialPassword);
            result.put("nextcloudUrl", nextcloudUrl);
            result.put("quota", quota);
            result.put("passwordShownOnce", true);
            return result;
        }
    }

    private static final class OcsResponse {
        final long code;
        final String status;
        final String message;
        final Map<String, Object> data;

        OcsResponse(long code, String status, String message, Map<String, Object> data) {
            this.code = code;
            this.status = status == null ? "" : status;
            this.message = message == null ? "" : message;
            this.data = data;
        }
    }

    interface ProvisioningTransport {
        TransportResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    interface ProfileVerifier {
        void verify(Profile profile) throws IOException, InterruptedException, HttpError;
    }

    static final class TransportResponse {
        final int statusCode;
        final InputStream body;

        TransportResponse(int statusCode, InputStream body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        static TransportResponse json(int statusCode, String body) {
            return new TransportResponse(statusCode,
                new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static final class JavaHttpTransport implements ProvisioningTransport {
        private final HttpClient http;

        JavaHttpTransport(AppConfig config) {
            http = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        }

        @Override
        public TransportResponse send(HttpRequest request) throws IOException, InterruptedException {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new TransportResponse(response.statusCode(), response.body());
        }
    }
}
