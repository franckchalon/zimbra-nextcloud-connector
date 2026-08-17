package fr.franckchalon.zimbra.nextcloud;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

final class AppConfig {
    static final String DEFAULT_CONFIG_PATH = "/opt/zimbra/conf/nextcloud-zimlet.properties";

    final Path storageDirectory;
    final boolean sharedStorage;
    final Path customTemplatesDirectory;
    final String defaultLanguage;
    final boolean remoteBackgroundsAllowed;
    final String zimbraPublicUrl;
    final String nextcloudAccountMode;
    final String managedNextcloudUrl;
    final String managedAdminUsername;
    final String managedAdminAppPassword;
    final String managedGroup;
    final String managedQuota;
    final String managedLanguage;
    final String officeProvider;
    final String officeDisplayName;
    final String officeConnectorAppId;
    final String officePublicUrl;
    final Set<String> officeDownloadHosts;
    final String officeSecurityMode;
    final String officeJwtSecret;
    final String officeJwtHeader;
    final String ticketSecret;
    final long ticketTtlSeconds;
    final long maxUploadBytes;
    final long uploadChunkBytes;
    final long maxOfficeDownloadBytes;
    final int maxConcurrentRequests;
    final int maxRequestsPerMinute;
    final int maxDirectoryResponseItems;
    final int fallbackAttachmentCount;
    final long fallbackAttachmentBytes;
    final Duration connectTimeout;
    final Duration requestTimeout;
    final Duration talkRequestTimeout;
    final boolean nextcloudAllowHttp;
    final boolean nextcloudBlockPrivateNetworks;
    final Set<String> nextcloudPrivateHosts;
    final boolean officeAllowHttpDownloads;

    private AppConfig(Properties properties) {
        storageDirectory = Paths.get(require(properties, "storage.dir")).toAbsolutePath().normalize();
        sharedStorage = Boolean.parseBoolean(properties.getProperty("storage.shared", "false"));
        String templatesPath = properties.getProperty("templates.dir", "").trim();
        customTemplatesDirectory = templatesPath.isEmpty() ? null : Paths.get(templatesPath).toAbsolutePath().normalize();
        defaultLanguage = normalizeLanguage(properties.getProperty("ui.default_language", "fr"));
        remoteBackgroundsAllowed = Boolean.parseBoolean(properties.getProperty("ui.remote_backgrounds", "false"));
        zimbraPublicUrl = normalizeBaseUrl(require(properties, "zimbra.public_url"), false);
        nextcloudAccountMode = normalizeAccountMode(properties.getProperty("nextcloud.account_mode", "manual"));
        managedNextcloudUrl = "managed".equals(nextcloudAccountMode)
            ? normalizeBaseUrl(require(properties, "managed.nextcloud_url"), false) : "";
        managedAdminUsername = "managed".equals(nextcloudAccountMode)
            ? require(properties, "managed.admin_username") : "";
        managedAdminAppPassword = "managed".equals(nextcloudAccountMode)
            ? require(properties, "managed.admin_app_password") : "";
        managedGroup = properties.getProperty("managed.group", "").trim();
        managedQuota = properties.getProperty("managed.quota", "").trim();
        managedLanguage = properties.getProperty("managed.language", "fr").trim();
        officeProvider = normalizeOfficeProvider(properties.getProperty("office.provider", "onlyoffice"));
        officeDisplayName = "eurooffice".equals(officeProvider) ? "Euro-Office" : "ONLYOFFICE";
        officeConnectorAppId = "eurooffice".equals(officeProvider) ? "eurooffice" : "onlyoffice";
        officePublicUrl = normalizeBaseUrl(firstNonBlank(properties, "office.public_url", "onlyoffice.public_url"), false);
        officeDownloadHosts = parseHosts(firstNonBlankOrDefault(properties,
            "office.download_hosts", "onlyoffice.download_hosts", hostOf(officePublicUrl)
        ));
        officeSecurityMode = normalizeSecurityMode(properties.getProperty("office.security_mode", "jwt"));
        officeJwtSecret = firstNonBlankOrDefault(properties, "office.jwt_secret", "onlyoffice.jwt_secret", "");
        officeJwtHeader = firstNonBlankOrDefault(properties, "office.jwt_header", "onlyoffice.jwt_header", "Authorization");
        ticketSecret = require(properties, "security.ticket_secret");
        ticketTtlSeconds = positiveLong(properties, "security.ticket_ttl_seconds", 604800L);
        maxUploadBytes = positiveLong(properties, "files.max_upload_bytes", 1073741824L);
        uploadChunkBytes = boundedLong(properties, "files.upload_chunk_bytes", 8L * 1024L * 1024L,
            5L * 1024L * 1024L, 512L * 1024L * 1024L);
        maxOfficeDownloadBytes = positiveLong(properties, "files.max_office_download_bytes",
            positiveLong(properties, "files.max_onlyoffice_download_bytes", maxUploadBytes));
        maxConcurrentRequests = boundedInt(properties, "limits.max_concurrent_requests", 24, 4, 256);
        maxRequestsPerMinute = boundedInt(properties, "limits.max_requests_per_account_minute", 600, 60, 10000);
        maxDirectoryResponseItems = boundedInt(properties, "limits.max_directory_response_items", 5000, 200, 50000);
        fallbackAttachmentCount = boundedInt(properties, "mail.fallback_max_attachments", 20, 1, 200);
        fallbackAttachmentBytes = positiveLong(properties, "mail.fallback_max_attachment_bytes", 100L * 1024L * 1024L);
        connectTimeout = Duration.ofSeconds(positiveLong(properties, "http.connect_timeout_seconds", 15L));
        requestTimeout = Duration.ofSeconds(positiveLong(properties, "http.request_timeout_seconds", 1800L));
        talkRequestTimeout = Duration.ofSeconds(boundedLong(properties, "talk.request_timeout_seconds", 8L, 3L, 60L));
        nextcloudAllowHttp = Boolean.parseBoolean(properties.getProperty("nextcloud.allow_http", "false"));
        nextcloudBlockPrivateNetworks = Boolean.parseBoolean(properties.getProperty("nextcloud.block_private_networks", "true"));
        nextcloudPrivateHosts = parseHostsOptional(properties.getProperty("nextcloud.private_hosts", ""));
        officeAllowHttpDownloads = Boolean.parseBoolean(firstNonBlankOrDefault(properties,
            "office.allow_http_downloads", "onlyoffice.allow_http_downloads", "false"));

        if (officeJwtEnabled() && officeJwtSecret.length() < 24) {
            throw new IllegalArgumentException("office.jwt_secret doit contenir au moins 24 caractères en mode JWT");
        }
        if (!officeJwtHeader.matches("[A-Za-z0-9-]{1,80}")) {
            throw new IllegalArgumentException("office.jwt_header est invalide");
        }
        if (ticketSecret.length() < 32) {
            throw new IllegalArgumentException("security.ticket_secret doit contenir au moins 32 caractères");
        }
        if (managedAdminUsername.length() > 320 || managedAdminAppPassword.length() > 4096) {
            throw new IllegalArgumentException("Identifiants du compte de service Nextcloud invalides");
        }
        if (!managedGroup.matches("[^\\p{Cntrl}]{0,255}")) {
            throw new IllegalArgumentException("managed.group est invalide");
        }
        if (!managedQuota.matches("[^\\p{Cntrl}]{0,64}")) {
            throw new IllegalArgumentException("managed.quota est invalide");
        }
        if (!managedLanguage.matches("[A-Za-z]{2,3}(?:[_-][A-Za-z]{2,8})?")) {
            throw new IllegalArgumentException("managed.language est invalide");
        }
        if (customTemplatesDirectory != null && customTemplatesDirectory.startsWith(storageDirectory.resolve("profiles"))) {
            throw new IllegalArgumentException("templates.dir ne doit pas se trouver dans le dossier des profils chiffrés");
        }
    }

    static AppConfig load() throws IOException {
        String configured = System.getProperty("nextcloud.zimlet.config", DEFAULT_CONFIG_PATH);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Paths.get(configured))) {
            properties.load(input);
        }
        return new AppConfig(properties);
    }

    String validateNextcloudUrl(String raw) {
        String normalized = normalizeBaseUrl(raw, nextcloudAllowHttp);
        URI uri = URI.create(normalized);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (nextcloudBlockPrivateNetworks && !nextcloudPrivateHosts.contains(host) && resolvesToPrivateNetwork(host)) {
            throw new IllegalArgumentException(
                "Cette adresse Nextcloud pointe vers un réseau privé. L’administrateur doit l’autoriser explicitement."
            );
        }
        return normalized;
    }

    String validateOfficeProfileUrl(String raw) {
        String normalized = normalizeBaseUrl(raw, false);
        URI uri = URI.create(normalized);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (nextcloudBlockPrivateNetworks && !nextcloudPrivateHosts.contains(host)
                && !officeDownloadHosts.contains(host) && resolvesToPrivateNetwork(host)) {
            throw new IllegalArgumentException(
                "Cette adresse de serveur bureautique pointe vers un réseau privé. L’administrateur doit l’autoriser explicitement."
            );
        }
        return normalized;
    }

    boolean officeJwtEnabled() {
        return "jwt".equals(officeSecurityMode);
    }

    boolean managedAccountsEnabled() {
        return "managed".equals(nextcloudAccountMode);
    }

    URI validateOfficeDownloadUrl(String raw) {
        return validateOfficeDownloadUrl(raw, OfficeSettings.global(this));
    }

    URI validateOfficeDownloadUrl(String raw, OfficeSettings office) {
        URI uri;
        try { uri = new URI(raw); }
        catch (URISyntaxException e) {
            throw new IllegalArgumentException("Adresse de téléchargement " + office.displayName + " invalide", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(officeAllowHttpDownloads && "http".equals(scheme))) {
            throw new IllegalArgumentException("Le téléchargement " + office.displayName + " doit utiliser HTTPS");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!office.downloadHosts.contains(host)) {
            throw new IllegalArgumentException("L’hôte de téléchargement " + office.displayName + " n’est pas autorisé");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Adresse " + office.displayName + " interdite");
        }
        return uri;
    }

    String validateOfficeEditorUrl(String raw) {
        return validateOfficeEditorUrl(raw, OfficeSettings.global(this));
    }

    String validateOfficeEditorUrl(String raw, OfficeSettings office) {
        String normalized = normalizeBaseUrl(raw, false);
        if (!office.publicUrl.equals(normalized)) {
            throw new IllegalArgumentException(
                "Le serveur " + office.displayName + " annoncé par Nextcloud ne correspond pas à celui configuré pour ce compte Cloud"
            );
        }
        return normalized;
    }

    private static String normalizeBaseUrl(String raw, boolean allowHttp) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Adresse de serveur manquante");
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || (allowHttp && "http".equals(scheme)))) {
                throw new IllegalArgumentException("L’adresse doit commencer par https://");
            }
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Adresse de serveur invalide");
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), path, null, null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Adresse de serveur invalide", e);
        }
    }

    private static boolean resolvesToPrivateNetwork(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")) return true;
        final InetAddress[] addresses;
        try { addresses = InetAddress.getAllByName(host); }
        catch (UnknownHostException e) {
            throw new IllegalArgumentException("Impossible de résoudre l’adresse du serveur Nextcloud", e);
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress() || isAdditionalPrivateRange(address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdditionalPrivateRange(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first == 100 && second >= 64 && second <= 127)
                || (first == 198 && (second == 18 || second == 19));
        }
        return bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc);
    }

    private static Set<String> parseHosts(String value) {
        Set<String> result = parseHostsOptional(value);
        if (result.isEmpty()) throw new IllegalArgumentException("La liste d’hôtes du serveur bureautique est vide");
        return result;
    }

    static String normalizeOfficeProvider(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace("-", "");
        if ("onlyoffice".equals(value)) return "onlyoffice";
        if ("eurooffice".equals(value)) return "eurooffice";
        throw new IllegalArgumentException("office.provider doit valoir onlyoffice ou eurooffice");
    }

    private static String normalizeAccountMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("manual".equals(value) || "managed".equals(value)) return value;
        throw new IllegalArgumentException("nextcloud.account_mode doit valoir manual ou managed");
    }

    static String normalizeSecurityMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("jwt".equals(value) || "none".equals(value)) return value;
        throw new IllegalArgumentException("office.security_mode doit valoir jwt ou none");
    }

    private static String normalizeLanguage(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("fr".equals(value) || "fr-fr".equals(value)) return "fr";
        if (value.startsWith("en")) return "en-US";
        if ("es-ar".equals(value)) return "es-AR";
        if (value.startsWith("es")) return "es";
        if (value.startsWith("it")) return "it";
        if (value.startsWith("de")) return "de";
        if ("pt-br".equals(value)) return "pt-BR";
        if (value.startsWith("pt")) return "pt-PT";
        if (value.startsWith("hi")) return "hi-IN";
        if (value.startsWith("ms")) return "ms-MY";
        if (value.startsWith("ru")) return "ru-RU";
        throw new IllegalArgumentException("ui.default_language doit valoir fr, en-US, es, es-AR, it, de, pt-PT, pt-BR, hi-IN, ms-MY ou ru-RU");
    }

    private static String firstNonBlank(Properties properties, String preferred, String legacy) {
        String value = properties.getProperty(preferred, "").trim();
        if (value.isEmpty()) value = properties.getProperty(legacy, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Paramètre obligatoire manquant : " + preferred);
        return value;
    }

    private static String firstNonBlankOrDefault(Properties properties, String preferred, String legacy, String fallback) {
        String value = properties.getProperty(preferred, "").trim();
        if (value.isEmpty()) value = properties.getProperty(legacy, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static Set<String> parseHostsOptional(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(value.split(","))
            .map(String::trim)
            .map(item -> item.toLowerCase(Locale.ROOT))
            .filter(item -> !item.isBlank())
            .forEach(result::add);
        return Collections.unmodifiableSet(result);
    }

    private static String hostOf(String url) {
        return URI.create(url).getHost();
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Paramètre obligatoire manquant : " + key);
        return value;
    }

    private static long positiveLong(Properties properties, String key, long fallback) {
        String value = properties.getProperty(key);
        long result = value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        if (result <= 0) throw new IllegalArgumentException(key + " doit être supérieur à zéro");
        return result;
    }

    private static long boundedLong(Properties properties, String key, long fallback, long minimum, long maximum) {
        long result = positiveLong(properties, key, fallback);
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(key + " doit être compris entre " + minimum + " et " + maximum);
        }
        return result;
    }

    private static int boundedInt(Properties properties, String key, int fallback, int minimum, int maximum) {
        String value = properties.getProperty(key);
        int result = value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(key + " doit être compris entre " + minimum + " et " + maximum);
        }
        return result;
    }
}
