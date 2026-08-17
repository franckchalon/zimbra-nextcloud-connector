package fr.franckchalon.zimbra.nextcloud;

/** Locale mapping supported by ONLYOFFICE and Euro-Office Docs APIs. */
final class EditorLocale {
    final String documentLocale;
    final String editorLanguage;
    final String editorRegion;

    private EditorLocale(String documentLocale, String editorLanguage, String editorRegion) {
        this.documentLocale = documentLocale;
        this.editorLanguage = editorLanguage;
        this.editorRegion = editorRegion;
    }

    static EditorLocale from(String raw) {
        String locale = ServerI18n.canonicalLocale(raw, "fr-FR");
        switch (locale) {
            case "pt-PT": return new EditorLocale(locale, "pt-PT", "pt-PT");
            case "pt-BR": return new EditorLocale(locale, "pt", "pt-BR");
            case "es-AR": return new EditorLocale(locale, "es", "es-ES");
            case "es-ES": return new EditorLocale(locale, "es", "es-ES");
            case "hi-IN": return new EditorLocale(locale, "en", "en-US");
            case "ms-MY": return new EditorLocale(locale, "ms", "en-US");
            case "ru-RU": return new EditorLocale(locale, "ru", "ru-RU");
            case "en-US": return new EditorLocale(locale, "en", "en-US");
            case "it-IT": return new EditorLocale(locale, "it", "it-IT");
            case "de-DE": return new EditorLocale(locale, "de", "de-DE");
            default: return new EditorLocale("fr-FR", "fr", "fr-FR");
        }
    }
}
