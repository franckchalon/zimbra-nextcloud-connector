package fr.franckchalon.zimbra.nextcloud;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class Crypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Crypto() {}

    static byte[] randomBytes(int length) {
        byte[] result = new byte[length];
        RANDOM.nextBytes(result);
        return result;
    }

    static String encrypt(byte[] key, String aad, String plaintext) throws GeneralSecurityException {
        byte[] nonce = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        ByteBuffer packed = ByteBuffer.allocate(1 + nonce.length + encrypted.length);
        packed.put((byte) 1).put(nonce).put(encrypted);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(packed.array());
    }

    static String decrypt(byte[] key, String aad, String encoded) throws GeneralSecurityException {
        byte[] packed = Base64.getUrlDecoder().decode(encoded);
        if (packed.length < 30 || packed[0] != 1) throw new GeneralSecurityException("Format chiffré invalide");
        byte[] nonce = new byte[12];
        System.arraycopy(packed, 1, nonce, 0, nonce.length);
        byte[] ciphertext = new byte[packed.length - 13];
        System.arraycopy(packed, 13, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    static byte[] hmacSha256(byte[] secret, String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format("%02x", item & 0xff));
            return out.toString();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean constantTimeEquals(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left, right);
    }
}

