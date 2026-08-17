package fr.franckchalon.zimbra.nextcloud;

import java.net.URI;
import java.util.Set;

/** Resolved, validated office settings used for one Nextcloud profile. */
final class OfficeSettings {
    final String mode;
    final String provider;
    final String displayName;
    final String connectorAppId;
    final String publicUrl;
    final String securityMode;
    final String jwtHeader;
    final String jwtSecret;
    final Set<String> downloadHosts;

    private OfficeSettings(String mode, String provider, String publicUrl, String securityMode,
            String jwtHeader, String jwtSecret, Set<String> downloadHosts) {
        this.mode = mode;
        this.provider = provider;
        this.displayName = "eurooffice".equals(provider) ? "Euro-Office" : "ONLYOFFICE";
        this.connectorAppId = "eurooffice".equals(provider) ? "eurooffice" : "onlyoffice";
        this.publicUrl = publicUrl;
        this.securityMode = securityMode;
        this.jwtHeader = jwtHeader;
        this.jwtSecret = jwtSecret;
        this.downloadHosts = downloadHosts;
    }

    static OfficeSettings global(AppConfig config) {
        return new OfficeSettings(
            OfficeProfile.GLOBAL,
            config.officeProvider,
            config.officePublicUrl,
            config.officeSecurityMode,
            config.officeJwtHeader,
            config.officeJwtSecret,
            config.officeDownloadHosts
        );
    }

    static OfficeSettings custom(AppConfig config, OfficeProfile profile) {
        String host = URI.create(profile.publicUrl).getHost().toLowerCase(java.util.Locale.ROOT);
        return new OfficeSettings(
            OfficeProfile.CUSTOM,
            profile.provider,
            profile.publicUrl,
            profile.securityMode,
            profile.jwtHeader,
            profile.jwtSecret,
            Set.of(host)
        );
    }

    boolean jwtEnabled() {
        return "jwt".equals(securityMode);
    }
}
