package fr.franckchalon.zimbra.nextcloud;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

final class PathUtil {
    private PathUtil() {}

    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return "/";
        if (raw.length() > 4096) throw new IllegalArgumentException("Chemin trop long");
        String value = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC).replace('\\', '/');
        if (!value.startsWith("/")) value = "/" + value;
        StringBuilder out = new StringBuilder();
        for (String part : value.split("/")) {
            if (part.isEmpty()) continue;
            validateName(part);
            out.append('/').append(part);
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    static void validateName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Nom vide");
        if (name.equals(".") || name.equals("..")) throw new IllegalArgumentException("Nom interdit");
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Le nom contient un caractère interdit");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) throw new IllegalArgumentException("Le nom contient un caractère de contrôle");
        }
    }

    static String join(String directory, String name) {
        validateName(name);
        String parent = normalize(directory);
        return normalize((parent.equals("/") ? "" : parent) + "/" + name);
    }

    static String fileName(String path) {
        String normalized = normalize(path);
        if (normalized.equals("/")) return "fichier";
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    static String parent(String path) {
        String normalized = normalize(path);
        if ("/".equals(normalized)) return "/";
        int separator = normalized.lastIndexOf('/');
        return separator <= 0 ? "/" : normalized.substring(0, separator);
    }

    static String extension(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%7E", "~");
    }

    static String encodePath(String path) {
        String normalized = normalize(path);
        if (normalized.equals("/")) return "";
        StringBuilder out = new StringBuilder();
        for (String part : normalized.substring(1).split("/")) out.append('/').append(encodeSegment(part));
        return out.toString();
    }
}
