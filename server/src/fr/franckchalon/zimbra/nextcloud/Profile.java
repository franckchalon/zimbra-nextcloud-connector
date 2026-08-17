package fr.franckchalon.zimbra.nextcloud;

import java.util.LinkedHashMap;
import java.util.Map;

final class Profile {
    final String id;
    final String label;
    final String nextcloudUrl;
    final String username;
    final String appPassword;
    final long updatedAt;
    final boolean managed;
    final OfficeProfile office;

    Profile(String nextcloudUrl, String username, String appPassword, long updatedAt) {
        this(stableId(nextcloudUrl, username), "", nextcloudUrl, username, appPassword, updatedAt, false,
            OfficeProfile.global());
    }

    Profile(String id, String label, String nextcloudUrl, String username, String appPassword,
            long updatedAt, boolean managed) {
        this(id, label, nextcloudUrl, username, appPassword, updatedAt, managed, OfficeProfile.global());
    }

    Profile(String id, String label, String nextcloudUrl, String username, String appPassword,
            long updatedAt, boolean managed, OfficeProfile office) {
        this.id = validId(id) ? id : stableId(nextcloudUrl, username);
        this.label = label == null ? "" : label.trim();
        this.nextcloudUrl = nextcloudUrl;
        this.username = username;
        this.appPassword = appPassword;
        this.updatedAt = updatedAt;
        this.managed = managed;
        this.office = office == null ? OfficeProfile.global() : office;
    }

    Map<String, Object> toStorageMap() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("label", label);
        result.put("nextcloudUrl", nextcloudUrl);
        result.put("username", username);
        result.put("appPassword", appPassword);
        result.put("updatedAt", updatedAt);
        result.put("managed", managed);
        result.put("office", office.toStorageMap());
        return result;
    }

    static Profile fromStorageMap(Map<String, Object> source) {
        return new Profile(
            Json.string(source, "id", ""),
            Json.string(source, "label", ""),
            Json.string(source, "nextcloudUrl", ""),
            Json.string(source, "username", ""),
            Json.string(source, "appPassword", ""),
            Json.longValue(source, "updatedAt", 0),
            Boolean.TRUE.equals(source.get("managed")),
            OfficeProfile.fromStorage(source.get("office"))
        );
    }

    Profile withIdentity(String requestedId, String requestedLabel, boolean requestedManaged) {
        return new Profile(
            validId(requestedId) ? requestedId : id,
            requestedLabel,
            nextcloudUrl,
            username,
            appPassword,
            updatedAt,
            requestedManaged,
            office
        );
    }

    private static String stableId(String nextcloudUrl, String username) {
        return "nc-" + Crypto.sha256Hex(String.valueOf(nextcloudUrl) + "\n" + String.valueOf(username)).substring(0, 16);
    }

    static boolean validId(String value) {
        return value != null && value.matches("nc-[a-f0-9]{16,48}");
    }
}
