package fr.franckchalon.zimbra.nextcloud;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DocumentTemplateService {
    private static final long MAX_TEMPLATE_BYTES = 32L * 1024L * 1024L;
    private static final List<String> KINDS = List.of("docx", "xlsx", "pptx", "odt", "ods", "odp");
    private final AppConfig config;

    DocumentTemplateService(AppConfig config) { this.config = config; }

    List<Map<String, Object>> list() throws IOException {
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        for (String kind : KINDS) {
            LinkedHashMap<String, Object> builtIn = new LinkedHashMap<>();
            builtIn.put("id", "");
            builtIn.put("name", "Blank " + kind.toUpperCase(Locale.ROOT));
            builtIn.put("kind", kind);
            builtIn.put("builtIn", true);
            result.add(builtIn);
        }
        Path directory = config.customTemplatesDirectory;
        if (directory != null && Files.isDirectory(directory)) {
            try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
                paths.filter(Files::isRegularFile)
                    .filter(path -> KINDS.contains(PathUtil.extension(path.getFileName().toString())))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .limit(200)
                    .forEach(path -> {
                        LinkedHashMap<String, Object> custom = new LinkedHashMap<>();
                        String filename = path.getFileName().toString();
                        custom.put("id", filename);
                        custom.put("name", filename.substring(0, filename.lastIndexOf('.')));
                        custom.put("kind", PathUtil.extension(filename));
                        custom.put("builtIn", false);
                        result.add(custom);
                    });
            }
        }
        return result;
    }

    byte[] load(String kind, String templateId, String language) throws IOException, HttpError {
        if (!KINDS.contains(kind)) throw new HttpError(400, "Type de modèle de document inconnu");
        if (templateId == null || templateId.isBlank()) {
            try (InputStream template = getClass().getResourceAsStream("/templates/blank." + kind)) {
                if (template == null) throw new HttpError(500, "Modèle de document absent du module serveur");
                return DocumentTemplateLocalizer.localize(readLimited(template), kind, language);
            }
        }
        PathUtil.validateName(templateId);
        if (!kind.equals(PathUtil.extension(templateId))) throw new HttpError(400, "Le modèle ne correspond pas au format choisi");
        Path directory = config.customTemplatesDirectory;
        if (directory == null) throw new HttpError(404, "Modèle de document personnalisé introuvable");
        Path path = directory.resolve(templateId).normalize();
        if (!path.getParent().equals(directory) || !Files.isRegularFile(path)) {
            throw new HttpError(404, "Modèle de document personnalisé introuvable");
        }
        long size = Files.size(path);
        if (size <= 0L || size > MAX_TEMPLATE_BYTES) throw new HttpError(413, "Modèle de document trop volumineux");
        return Files.readAllBytes(path);
    }

    private static byte[] readLimited(InputStream input) throws IOException, HttpError {
        byte[] bytes = input.readNBytes((int) MAX_TEMPLATE_BYTES + 1);
        if (bytes.length > MAX_TEMPLATE_BYTES) throw new HttpError(413, "Modèle de document trop volumineux");
        return bytes;
    }
}
