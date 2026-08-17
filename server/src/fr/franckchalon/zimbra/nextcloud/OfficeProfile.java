package fr.franckchalon.zimbra.nextcloud;

import java.util.LinkedHashMap;
import java.util.Map;

/** Optional office editor override stored inside an encrypted Nextcloud profile. */
final class OfficeProfile {
    static final String GLOBAL = "global";
    static final String CUSTOM = "custom";

    final String mode;
    final String provider;
    final String publicUrl;
    final String securityMode;
    final String jwtHeader;
    final String jwtSecret;

    private OfficeProfile(String mode, String provider, String publicUrl, String securityMode,
            String jwtHeader, String jwtSecret) {
        this.mode = CUSTOM.equals(mode) ? CUSTOM : GLOBAL;
        this.provider = provider == null ? "" : provider;
        this.publicUrl = publicUrl == null ? "" : publicUrl;
        this.securityMode = securityMode == null ? "" : securityMode;
        this.jwtHeader = jwtHeader == null ? "" : jwtHeader;
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret;
    }

    static OfficeProfile global() {
        return new OfficeProfile(GLOBAL, "", "", "", "", "");
    }

    static OfficeProfile custom(AppConfig config, String provider, String publicUrl,
            String securityMode, String jwtHeader, String jwtSecret) {
        String normalizedProvider = AppConfig.normalizeOfficeProvider(provider);
        String normalizedUrl = config.validateOfficeProfileUrl(publicUrl);
        String normalizedSecurity = AppConfig.normalizeSecurityMode(securityMode);
        String normalizedHeader = jwtHeader == null || jwtHeader.isBlank() ? "Authorization" : jwtHeader.trim();
        String normalizedSecret = "jwt".equals(normalizedSecurity) && jwtSecret != null ? jwtSecret : "";
        if (!normalizedHeader.matches("[A-Za-z0-9-]{1,80}")) {
            throw new IllegalArgumentException("office.jwt_header est invalide");
        }
        if ("jwt".equals(normalizedSecurity) && normalizedSecret.length() < 32) {
            throw new IllegalArgumentException("Le secret JWT personnalisé doit contenir au moins 32 caractères");
        }
        if (normalizedSecret.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Le secret JWT personnalisé ne doit contenir aucun espace");
        }
        return new OfficeProfile(CUSTOM, normalizedProvider, normalizedUrl, normalizedSecurity,
            normalizedHeader, normalizedSecret);
    }

    OfficeSettings resolve(AppConfig config) {
        return CUSTOM.equals(mode) ? OfficeSettings.custom(config, this) : OfficeSettings.global(config);
    }

    Map<String, Object> toStorageMap() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        if (CUSTOM.equals(mode)) {
            result.put("provider", provider);
            result.put("publicUrl", publicUrl);
            result.put("securityMode", securityMode);
            result.put("jwtHeader", jwtHeader);
            result.put("jwtSecret", jwtSecret);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static OfficeProfile fromStorage(Object value) {
        if (!(value instanceof Map)) return global();
        Map<String, Object> source = (Map<String, Object>) value;
        if (!CUSTOM.equals(Json.string(source, "mode", GLOBAL))) return global();
        return new OfficeProfile(
            CUSTOM,
            Json.string(source, "provider", "onlyoffice"),
            Json.string(source, "publicUrl", ""),
            Json.string(source, "securityMode", "jwt"),
            Json.string(source, "jwtHeader", "Authorization"),
            Json.string(source, "jwtSecret", "")
        );
    }
}
