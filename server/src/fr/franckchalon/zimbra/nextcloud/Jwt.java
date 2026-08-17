package fr.franckchalon.zimbra.nextcloud;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class Jwt {
    private Jwt() {}

    static String sign(Map<String, Object> payload, String secret) throws GeneralSecurityException {
        LinkedHashMap<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        String first = encode(Json.stringify(header));
        String second = encode(Json.stringify(payload));
        String signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Crypto.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), first + "." + second));
        return first + "." + second + "." + signature;
    }

    static Map<String, Object> verify(String token, String secret) throws GeneralSecurityException {
        if (token == null) throw new GeneralSecurityException("Jeton manquant");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw new GeneralSecurityException("Jeton invalide");
        byte[] expected = Crypto.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), parts[0] + "." + parts[1]);
        byte[] received;
        try { received = Base64.getUrlDecoder().decode(parts[2]); }
        catch (IllegalArgumentException e) { throw new GeneralSecurityException("Signature invalide", e); }
        if (!Crypto.constantTimeEquals(expected, received)) throw new GeneralSecurityException("Signature invalide");

        Map<String, Object> header = Json.parseObject(decode(parts[0]));
        if (!"HS256".equals(Json.string(header, "alg", ""))) throw new GeneralSecurityException("Algorithme JWT interdit");
        Map<String, Object> payload = Json.parseObject(decode(parts[1]));
        long exp = Json.longValue(payload, "exp", 0);
        if (exp > 0 && Instant.now().getEpochSecond() > exp) throw new GeneralSecurityException("Jeton expiré");
        return payload;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) throws GeneralSecurityException {
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw new GeneralSecurityException("JWT non décodable", e); }
    }
}

