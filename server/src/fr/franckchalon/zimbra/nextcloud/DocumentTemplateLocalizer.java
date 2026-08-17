package fr.franckchalon.zimbra.nextcloud;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Applies the Zimbra user's writing locale to newly-created office documents. */
final class DocumentTemplateLocalizer {
    private DocumentTemplateLocalizer() {}

    static byte[] localize(byte[] template, String extension, String requestedLanguage) throws IOException {
        EditorLocale locale = EditorLocale.from(requestedLanguage);
        String language = locale.documentLocale.substring(0, 2);
        String country = locale.documentLocale.substring(3);
        ByteArrayOutputStream output = new ByteArrayOutputStream(template.length + 1024);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(template));
             ZipOutputStream archive = new ZipOutputStream(output)) {
            ZipEntry source;
            while ((source = input.getNextEntry()) != null) {
                byte[] content = input.readAllBytes();
                boolean xml = source.getName().endsWith(".xml");
                if (xml) {
                    String text = new String(content, StandardCharsets.UTF_8);
                    if ("odt".equals(extension) || "ods".equals(extension) || "odp".equals(extension)) {
                        text = text.replaceAll("fo:language=\"[^\"]*\"", "fo:language=\"" + language + "\"")
                            .replaceAll("fo:country=\"[^\"]*\"", "fo:country=\"" + country + "\"");
                    } else if ("docx".equals(extension) || "pptx".equals(extension)) {
                        text = text.replace("en-US", locale.documentLocale);
                    }
                    content = text.getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry target = new ZipEntry(source.getName());
                target.setTime(source.getTime());
                if (source.getComment() != null) target.setComment(source.getComment());
                if (source.getExtra() != null) target.setExtra(source.getExtra());
                if (source.getMethod() == ZipEntry.STORED && !xml) {
                    target.setMethod(ZipEntry.STORED);
                    target.setSize(content.length);
                    target.setCompressedSize(content.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(content);
                    target.setCrc(crc.getValue());
                }
                archive.putNextEntry(target);
                archive.write(content);
                archive.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
