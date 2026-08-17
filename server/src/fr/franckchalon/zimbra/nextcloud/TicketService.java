package fr.franckchalon.zimbra.nextcloud;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class TicketService {
    static final class Ticket {
        final String purpose;
        final String accountId;
        final String profileId;
        final String path;

        Ticket(String purpose, String accountId, String profileId, String path) {
            this.purpose = purpose;
            this.accountId = accountId;
            this.profileId = profileId;
            this.path = path;
        }
    }

    private final AppConfig config;

    TicketService(AppConfig config) { this.config = config; }

    String create(String purpose, String accountId, String path) throws GeneralSecurityException {
        return create(purpose, accountId, "", path);
    }

    String create(String purpose, String accountId, String profileId, String path) throws GeneralSecurityException {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("purpose", purpose);
        payload.put("accountId", accountId);
        payload.put("profileId", profileId == null ? "" : profileId);
        payload.put("path", PathUtil.normalize(path));
        payload.put("iat", Instant.now().getEpochSecond());
        payload.put("exp", Instant.now().getEpochSecond() + config.ticketTtlSeconds);
        return Jwt.sign(payload, config.ticketSecret);
    }

    Ticket verify(String token, String expectedPurpose) throws GeneralSecurityException {
        Map<String, Object> payload = Jwt.verify(token, config.ticketSecret);
        String purpose = Json.string(payload, "purpose", "");
        if (!expectedPurpose.equals(purpose)) throw new GeneralSecurityException("Type de ticket invalide");
        String accountId = Json.string(payload, "accountId", "");
        String profileId = Json.string(payload, "profileId", "");
        String path = Json.string(payload, "path", "");
        if (accountId.isBlank() || path.isBlank()) throw new GeneralSecurityException("Ticket incomplet");
        return new Ticket(purpose, accountId, profileId, PathUtil.normalize(path));
    }
}
