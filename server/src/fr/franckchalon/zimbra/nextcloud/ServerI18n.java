package fr.franckchalon.zimbra.nextcloud;

import java.util.Locale;

/** Request-scoped messages returned by the HTTP extension. */
final class ServerI18n {
    private static final ThreadLocal<String> REQUEST_LANGUAGE = new ThreadLocal<>();
    private static volatile String defaultLanguage = "fr";

    private ServerI18n() {}

    static void setDefaultLanguage(String value) {
        defaultLanguage = canonicalLocale(value, "fr-FR");
    }

    static void use(String value) {
        REQUEST_LANGUAGE.set(canonicalLocale(value, defaultLanguage));
    }

    static void clear() {
        REQUEST_LANGUAGE.remove();
    }

    static String language() {
        return normalize(locale(), "fr");
    }

    static String locale() {
        return canonicalLocale(REQUEST_LANGUAGE.get(), defaultLanguage);
    }

    static String normalize(String value, String fallback) {
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int separator = raw.indexOf('-');
        String language = separator < 0 ? raw : raw.substring(0, separator);
        if ("fr".equals(language) || "en".equals(language) || "es".equals(language)
            || "it".equals(language) || "de".equals(language) || "pt".equals(language)
            || "hi".equals(language) || "ms".equals(language) || "ru".equals(language)) return language;
        return fallback == null || fallback.isBlank() ? "fr" : normalize(fallback, "fr");
    }

    static String canonicalLocale(String value, String fallback) {
        String raw = value == null ? "" : value.trim().replace('_', '-');
        int comma = raw.indexOf(',');
        if (comma >= 0) raw = raw.substring(0, comma);
        int quality = raw.indexOf(';');
        if (quality >= 0) raw = raw.substring(0, quality);
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("pt-br")) return "pt-BR";
        if (lower.startsWith("pt")) return "pt-PT";
        if (lower.startsWith("es-ar")) return "es-AR";
        if (lower.startsWith("es")) return "es-ES";
        if (lower.startsWith("hi")) return "hi-IN";
        if (lower.startsWith("ms")) return "ms-MY";
        if (lower.startsWith("ru")) return "ru-RU";
        if (lower.startsWith("en")) return "en-US";
        if (lower.startsWith("it")) return "it-IT";
        if (lower.startsWith("de")) return "de-DE";
        if (lower.startsWith("fr")) return "fr-FR";
        if (fallback == null || fallback.isBlank() || fallback.equalsIgnoreCase(raw)) return "fr-FR";
        return canonicalLocale(fallback, "fr-FR");
    }

    static String localize(String message) {
        if ("fr".equals(language())) return message == null || message.isBlank() ? text("unknownError") : message;
        return text(classify(message));
    }

    static String text(String key) {
        switch (language()) {
            case "en": return english(key);
            case "es": return spanish(key);
            case "it": return italian(key);
            case "de": return german(key);
            case "pt": return "pt-BR".equals(locale()) ? portugueseBrazil(key) : portuguese(key);
            case "hi": return hindi(key);
            case "ms": return malay(key);
            case "ru": return russian(key);
            default: return french(key);
        }
    }

    private static String classify(String source) {
        String message = source == null ? "" : source.trim();
        String lower = message.toLowerCase(Locale.ROOT);
        if (message.isBlank()) return "unknownError";
        if (lower.contains("identifiant ou mot de passe") || lower.contains("identifiants du compte")) return "credentialsInvalid";
        if (lower.contains("session zimbra expir")) return "sessionExpired";
        if (lower.contains("session zimbra invalide") || lower.contains("compte zimbra introuvable")) return "sessionInvalid";
        if (lower.contains("configurez d’abord")) return "configureFirst";
        if (lower.contains("point d’accès inconnu")) return "endpointUnknown";
        if (lower.contains("requête refusée") || lower.contains("non authentifié")) return "requestDenied";
        if (lower.contains("jeton") || lower.contains("jwt") || lower.contains("chiffrement")) return "securityInvalid";
        if (lower.contains("taille maximale") || lower.contains("trop volumineu")) return "tooLarge";
        if (lower.contains("existe déjà") || lower.contains("portant ce nom")) return "conflict";
        if (lower.contains("introuvable") || lower.contains("n’existe plus") || lower.contains("absent du module")) return "notFound";
        if (lower.contains("date d’expiration") || lower.contains("expiration invalide")) return "expirationInvalid";
        if (lower.contains("mot de passe d’application") && lower.contains("obligatoire")) return "appPasswordRequired";
        if (lower.contains("identifiant nextcloud invalide")) return "usernameInvalid";
        if (lower.contains("compte nextcloud géré") || lower.contains("activation automatique")) return "managedRestriction";
        if (lower.contains("provisionnement automatique") && lower.contains("pas activé")) return "provisioningDisabled";
        if (lower.contains("possède déjà une connexion nextcloud")) return "alreadyConnected";
        if (lower.contains("un compte nextcloud existe déjà")) return "accountExists";
        if (lower.contains("trois comptes nextcloud")) return "maxAccounts";
        if (lower.contains("compte nextcloud inconnu")) return "accountUnknown";
        if (lower.contains("réseau privé") || lower.contains("résoudre l’adresse") || lower.contains("adresse de serveur")) return "invalidServerAddress";
        if (lower.contains("format de fichier") || lower.contains("extension du document")) return "unsupportedFormat";
        if (lower.contains("dossier ne peut pas être ouvert")) return "folderNotEditable";
        if (lower.contains("miniature")) return "thumbnailUnavailable";
        if (lower.contains("lien public") || lower.contains("partage nextcloud")) return "sharingFailed";
        if (lower.contains("fichiers supprimés") || lower.contains("restaurer") || lower.contains("supprimer définitivement") || lower.contains("vider")) return "trashFailed";
        if (lower.contains("recherche")) return "searchFailed";
        if (lower.contains("télécharger")) return "downloadFailed";
        if (lower.contains("envoyer le fichier")) return "uploadFailed";
        if (lower.contains("créer le document") || lower.contains("créer le dossier") || lower.contains("type de création")) return "createFailed";
        if (lower.contains("renommer") || lower.contains("déplacement du dossier")) return "moveFailed";
        if (lower.contains("coédition") || lower.contains("session onlyoffice") || lower.contains("session euro-office") || lower.contains("ouverture onlyoffice") || lower.contains("ouverture euro-office")) return "editorFailed";
        if (lower.contains("json") || lower.contains("nom vide") || lower.contains("nom interdit") || lower.contains("caractère interdit") || lower.contains("chemin trop long")) return "invalidRequest";
        if (lower.contains("configuration serveur") || lower.contains("paramètre obligatoire") || lower.contains("office.")) return "configInvalid";
        if (lower.contains("opération interrompue")) return "interrupted";
        if (lower.contains("connecteur nextcloud est indisponible")) return "unavailable";
        if (lower.contains("erreur interne")) return "internalError";
        if (lower.contains("nextcloud refuse cette opération")) return "forbidden";
        return "operationFailed";
    }

    private static String french(String key) {
        switch (key) {
            case "unknownError": return "Erreur inconnue";
            case "credentialsInvalid": return "Identifiant ou mot de passe d’application Nextcloud incorrect";
            case "sessionExpired": return "Session Zimbra expirée";
            case "sessionInvalid": return "Session Zimbra invalide";
            case "configureFirst": return "Configurez d’abord votre compte Nextcloud";
            case "endpointUnknown": return "Point d’accès inconnu";
            case "requestDenied": return "Requête refusée";
            case "securityInvalid": return "Jeton de sécurité invalide";
            case "tooLarge": return "Le contenu dépasse la taille maximale autorisée";
            case "conflict": return "Un élément portant ce nom existe déjà ou le dossier parent est absent";
            case "notFound": return "Élément Nextcloud introuvable";
            case "expirationInvalid": return "Date d’expiration invalide";
            case "appPasswordRequired": return "Le mot de passe d’application Nextcloud est obligatoire";
            case "usernameInvalid": return "Identifiant Nextcloud invalide";
            case "managedRestriction": return "Cette opération est gérée par l’administrateur Nextcloud";
            case "provisioningDisabled": return "Le provisionnement automatique Nextcloud n’est pas activé";
            case "alreadyConnected": return "Ce compte Zimbra possède déjà une connexion Nextcloud";
            case "accountExists": return "Un compte Nextcloud existe déjà avec cet identifiant";
            case "maxAccounts": return "Trois comptes Nextcloud au maximum peuvent être connectés";
            case "accountUnknown": return "Compte Nextcloud inconnu";
            case "invalidServerAddress": return "Adresse du serveur Nextcloud invalide ou non autorisée";
            case "unsupportedFormat": return "Ce format de fichier n’est pas pris en charge";
            case "folderNotEditable": return "Un dossier ne peut pas être ouvert dans l’éditeur";
            case "thumbnailUnavailable": return "Miniature Nextcloud indisponible";
            case "sharingFailed": return "Impossible de créer le lien public Nextcloud";
            case "trashFailed": return "L’opération sur la corbeille Nextcloud a échoué";
            case "searchFailed": return "La recherche Nextcloud a échoué";
            case "downloadFailed": return "Impossible de télécharger le fichier Nextcloud";
            case "uploadFailed": return "Impossible d’envoyer le fichier vers Nextcloud";
            case "createFailed": return "Impossible de créer l’élément dans Nextcloud";
            case "moveFailed": return "Impossible de déplacer ou renommer l’élément Nextcloud";
            case "editorFailed": return "Impossible d’ouvrir la session de coédition";
            case "invalidRequest": return "Requête invalide";
            case "configInvalid": return "Configuration serveur du connecteur invalide";
            case "interrupted": return "Opération interrompue";
            case "unavailable": return "Le connecteur Nextcloud est indisponible, mais Zimbra reste opérationnel";
            case "internalError": return "Erreur interne de la connexion Nextcloud";
            case "forbidden": return "Nextcloud refuse cette opération";
            default: return "L’opération n’a pas pu être effectuée";
        }
    }

    private static String english(String key) {
        switch (key) {
            case "unknownError": return "Unknown error"; case "credentialsInvalid": return "Incorrect Nextcloud username or app password";
            case "sessionExpired": return "Zimbra session expired"; case "sessionInvalid": return "Invalid Zimbra session";
            case "configureFirst": return "Configure your Nextcloud account first"; case "endpointUnknown": return "Unknown endpoint";
            case "requestDenied": return "Request denied"; case "securityInvalid": return "Invalid security token";
            case "tooLarge": return "The content exceeds the maximum allowed size"; case "conflict": return "An item with this name already exists or the parent folder is missing";
            case "notFound": return "Nextcloud item not found"; case "expirationInvalid": return "Invalid expiry date";
            case "appPasswordRequired": return "The Nextcloud app password is required"; case "usernameInvalid": return "Invalid Nextcloud username";
            case "managedRestriction": return "This operation is managed by the Nextcloud administrator"; case "provisioningDisabled": return "Automatic Nextcloud provisioning is not enabled";
            case "alreadyConnected": return "This Zimbra account already has a Nextcloud connection"; case "accountExists": return "A Nextcloud account already exists with this username";
            case "maxAccounts": return "No more than three Nextcloud accounts can be connected"; case "accountUnknown": return "Unknown Nextcloud account";
            case "invalidServerAddress": return "The Nextcloud server address is invalid or not allowed"; case "unsupportedFormat": return "This file format is not supported";
            case "folderNotEditable": return "A folder cannot be opened in the editor"; case "thumbnailUnavailable": return "Nextcloud thumbnail unavailable";
            case "sharingFailed": return "Unable to create the Nextcloud public link"; case "trashFailed": return "The Nextcloud trash operation failed";
            case "searchFailed": return "The Nextcloud search failed"; case "downloadFailed": return "Unable to download the Nextcloud file";
            case "uploadFailed": return "Unable to upload the file to Nextcloud"; case "createFailed": return "Unable to create the item in Nextcloud";
            case "moveFailed": return "Unable to move or rename the Nextcloud item"; case "editorFailed": return "Unable to open the co-editing session";
            case "invalidRequest": return "Invalid request"; case "configInvalid": return "Invalid server connector configuration";
            case "interrupted": return "Operation interrupted"; case "unavailable": return "The Nextcloud connector is unavailable, but Zimbra remains operational";
            case "internalError": return "Internal Nextcloud connection error"; case "forbidden": return "Nextcloud denied this operation";
            default: return "The operation could not be completed";
        }
    }

    private static String spanish(String key) {
        switch (key) {
            case "unknownError": return "Error desconocido"; case "credentialsInvalid": return "Usuario o contraseña de aplicación de Nextcloud incorrectos";
            case "sessionExpired": return "La sesión de Zimbra ha caducado"; case "sessionInvalid": return "Sesión de Zimbra no válida";
            case "configureFirst": return "Configure primero su cuenta Nextcloud"; case "endpointUnknown": return "Punto de acceso desconocido";
            case "requestDenied": return "Solicitud rechazada"; case "securityInvalid": return "Token de seguridad no válido";
            case "tooLarge": return "El contenido supera el tamaño máximo permitido"; case "conflict": return "Ya existe un elemento con este nombre o falta la carpeta superior";
            case "notFound": return "Elemento de Nextcloud no encontrado"; case "expirationInvalid": return "Fecha de caducidad no válida";
            case "appPasswordRequired": return "La contraseña de aplicación de Nextcloud es obligatoria"; case "usernameInvalid": return "Usuario de Nextcloud no válido";
            case "managedRestriction": return "Esta operación la gestiona el administrador de Nextcloud"; case "provisioningDisabled": return "El aprovisionamiento automático de Nextcloud no está activado";
            case "alreadyConnected": return "Esta cuenta Zimbra ya tiene una conexión Nextcloud"; case "accountExists": return "Ya existe una cuenta Nextcloud con este usuario";
            case "maxAccounts": return "Se pueden conectar como máximo tres cuentas Nextcloud"; case "accountUnknown": return "Cuenta Nextcloud desconocida";
            case "invalidServerAddress": return "La dirección del servidor Nextcloud no es válida o no está permitida"; case "unsupportedFormat": return "Este formato de archivo no es compatible";
            case "folderNotEditable": return "No se puede abrir una carpeta en el editor"; case "thumbnailUnavailable": return "Miniatura de Nextcloud no disponible";
            case "sharingFailed": return "No se pudo crear el enlace público de Nextcloud"; case "trashFailed": return "La operación de la papelera de Nextcloud ha fallado";
            case "searchFailed": return "La búsqueda de Nextcloud ha fallado"; case "downloadFailed": return "No se pudo descargar el archivo de Nextcloud";
            case "uploadFailed": return "No se pudo subir el archivo a Nextcloud"; case "createFailed": return "No se pudo crear el elemento en Nextcloud";
            case "moveFailed": return "No se pudo mover o cambiar el nombre del elemento de Nextcloud"; case "editorFailed": return "No se pudo abrir la sesión de coedición";
            case "invalidRequest": return "Solicitud no válida"; case "configInvalid": return "Configuración del conector del servidor no válida";
            case "interrupted": return "Operación interrumpida"; case "unavailable": return "El conector Nextcloud no está disponible, pero Zimbra sigue operativo";
            case "internalError": return "Error interno de conexión con Nextcloud"; case "forbidden": return "Nextcloud ha rechazado esta operación";
            default: return "No se pudo completar la operación";
        }
    }

    private static String italian(String key) {
        switch (key) {
            case "unknownError": return "Errore sconosciuto"; case "credentialsInvalid": return "Nome utente o password applicazione Nextcloud errati";
            case "sessionExpired": return "Sessione Zimbra scaduta"; case "sessionInvalid": return "Sessione Zimbra non valida";
            case "configureFirst": return "Configurare prima l’account Nextcloud"; case "endpointUnknown": return "Endpoint sconosciuto";
            case "requestDenied": return "Richiesta rifiutata"; case "securityInvalid": return "Token di sicurezza non valido";
            case "tooLarge": return "Il contenuto supera la dimensione massima consentita"; case "conflict": return "Esiste già un elemento con questo nome oppure manca la cartella superiore";
            case "notFound": return "Elemento Nextcloud non trovato"; case "expirationInvalid": return "Data di scadenza non valida";
            case "appPasswordRequired": return "La password applicazione Nextcloud è obbligatoria"; case "usernameInvalid": return "Nome utente Nextcloud non valido";
            case "managedRestriction": return "Questa operazione è gestita dall’amministratore Nextcloud"; case "provisioningDisabled": return "Il provisioning automatico Nextcloud non è attivo";
            case "alreadyConnected": return "Questo account Zimbra dispone già di una connessione Nextcloud"; case "accountExists": return "Esiste già un account Nextcloud con questo nome utente";
            case "maxAccounts": return "È possibile collegare al massimo tre account Nextcloud"; case "accountUnknown": return "Account Nextcloud sconosciuto";
            case "invalidServerAddress": return "L’indirizzo del server Nextcloud non è valido o non è consentito"; case "unsupportedFormat": return "Questo formato di file non è supportato";
            case "folderNotEditable": return "Una cartella non può essere aperta nell’editor"; case "thumbnailUnavailable": return "Miniatura Nextcloud non disponibile";
            case "sharingFailed": return "Impossibile creare il link pubblico Nextcloud"; case "trashFailed": return "Operazione sul cestino Nextcloud non riuscita";
            case "searchFailed": return "Ricerca Nextcloud non riuscita"; case "downloadFailed": return "Impossibile scaricare il file Nextcloud";
            case "uploadFailed": return "Impossibile caricare il file su Nextcloud"; case "createFailed": return "Impossibile creare l’elemento in Nextcloud";
            case "moveFailed": return "Impossibile spostare o rinominare l’elemento Nextcloud"; case "editorFailed": return "Impossibile aprire la sessione di modifica collaborativa";
            case "invalidRequest": return "Richiesta non valida"; case "configInvalid": return "Configurazione del connettore server non valida";
            case "interrupted": return "Operazione interrotta"; case "unavailable": return "Il connettore Nextcloud non è disponibile, ma Zimbra rimane operativo";
            case "internalError": return "Errore interno di connessione a Nextcloud"; case "forbidden": return "Nextcloud ha rifiutato questa operazione";
            default: return "Impossibile completare l’operazione";
        }
    }

    private static String german(String key) {
        switch (key) {
            case "unknownError": return "Unbekannter Fehler"; case "credentialsInvalid": return "Falscher Nextcloud-Benutzername oder falsches App-Passwort";
            case "sessionExpired": return "Zimbra-Sitzung abgelaufen"; case "sessionInvalid": return "Ungültige Zimbra-Sitzung";
            case "configureFirst": return "Konfigurieren Sie zuerst Ihr Nextcloud-Konto"; case "endpointUnknown": return "Unbekannter Endpunkt";
            case "requestDenied": return "Anfrage abgelehnt"; case "securityInvalid": return "Ungültiges Sicherheits-Token";
            case "tooLarge": return "Der Inhalt überschreitet die maximal zulässige Größe"; case "conflict": return "Ein Element mit diesem Namen ist bereits vorhanden oder der übergeordnete Ordner fehlt";
            case "notFound": return "Nextcloud-Element nicht gefunden"; case "expirationInvalid": return "Ungültiges Ablaufdatum";
            case "appPasswordRequired": return "Das Nextcloud-App-Passwort ist erforderlich"; case "usernameInvalid": return "Ungültiger Nextcloud-Benutzername";
            case "managedRestriction": return "Dieser Vorgang wird vom Nextcloud-Administrator verwaltet"; case "provisioningDisabled": return "Die automatische Nextcloud-Bereitstellung ist nicht aktiviert";
            case "alreadyConnected": return "Dieses Zimbra-Konto besitzt bereits eine Nextcloud-Verbindung"; case "accountExists": return "Ein Nextcloud-Konto mit diesem Benutzernamen ist bereits vorhanden";
            case "maxAccounts": return "Es können höchstens drei Nextcloud-Konten verbunden werden"; case "accountUnknown": return "Unbekanntes Nextcloud-Konto";
            case "invalidServerAddress": return "Die Nextcloud-Serveradresse ist ungültig oder nicht zulässig"; case "unsupportedFormat": return "Dieses Dateiformat wird nicht unterstützt";
            case "folderNotEditable": return "Ein Ordner kann nicht im Editor geöffnet werden"; case "thumbnailUnavailable": return "Nextcloud-Vorschaubild nicht verfügbar";
            case "sharingFailed": return "Der öffentliche Nextcloud-Link konnte nicht erstellt werden"; case "trashFailed": return "Der Nextcloud-Papierkorb-Vorgang ist fehlgeschlagen";
            case "searchFailed": return "Die Nextcloud-Suche ist fehlgeschlagen"; case "downloadFailed": return "Die Nextcloud-Datei konnte nicht heruntergeladen werden";
            case "uploadFailed": return "Die Datei konnte nicht zu Nextcloud hochgeladen werden"; case "createFailed": return "Das Element konnte nicht in Nextcloud erstellt werden";
            case "moveFailed": return "Das Nextcloud-Element konnte nicht verschoben oder umbenannt werden"; case "editorFailed": return "Die gemeinsame Bearbeitungssitzung konnte nicht geöffnet werden";
            case "invalidRequest": return "Ungültige Anfrage"; case "configInvalid": return "Ungültige Server-Connector-Konfiguration";
            case "interrupted": return "Vorgang unterbrochen"; case "unavailable": return "Der Nextcloud-Connector ist nicht verfügbar, Zimbra bleibt jedoch betriebsbereit";
            case "internalError": return "Interner Nextcloud-Verbindungsfehler"; case "forbidden": return "Nextcloud hat diesen Vorgang abgelehnt";
            default: return "Der Vorgang konnte nicht abgeschlossen werden";
        }
    }

    private static String portuguese(String key) {
        switch (key) {
            case "unknownError": return "Erro desconhecido"; case "credentialsInvalid": return "Nome de utilizador ou palavra-passe de aplicação Nextcloud incorretos";
            case "sessionExpired": return "A sessão Zimbra expirou"; case "sessionInvalid": return "Sessão Zimbra inválida";
            case "configureFirst": return "Configure primeiro a sua conta Nextcloud"; case "endpointUnknown": return "Ponto de acesso desconhecido";
            case "requestDenied": return "Pedido recusado"; case "securityInvalid": return "Token de segurança inválido";
            case "tooLarge": return "O conteúdo excede o tamanho máximo permitido"; case "conflict": return "Já existe um item com este nome ou a pasta superior não existe";
            case "notFound": return "Item Nextcloud não encontrado"; case "expirationInvalid": return "Data de expiração inválida";
            case "appPasswordRequired": return "A palavra-passe de aplicação Nextcloud é obrigatória"; case "usernameInvalid": return "Nome de utilizador Nextcloud inválido";
            case "managedRestriction": return "Esta operação é gerida pelo administrador Nextcloud"; case "provisioningDisabled": return "O aprovisionamento automático Nextcloud não está ativado";
            case "alreadyConnected": return "Esta conta Zimbra já tem uma ligação Nextcloud"; case "accountExists": return "Já existe uma conta Nextcloud com este nome de utilizador";
            case "maxAccounts": return "Só podem ser ligadas três contas Nextcloud"; case "accountUnknown": return "Conta Nextcloud desconhecida";
            case "invalidServerAddress": return "O endereço do servidor Nextcloud é inválido ou não autorizado"; case "unsupportedFormat": return "Este formato de ficheiro não é suportado";
            case "folderNotEditable": return "Não é possível abrir uma pasta no editor"; case "thumbnailUnavailable": return "Miniatura Nextcloud indisponível";
            case "sharingFailed": return "Não foi possível criar a ligação pública Nextcloud"; case "trashFailed": return "A operação no lixo Nextcloud falhou";
            case "searchFailed": return "A pesquisa Nextcloud falhou"; case "downloadFailed": return "Não foi possível transferir o ficheiro Nextcloud";
            case "uploadFailed": return "Não foi possível enviar o ficheiro para o Nextcloud"; case "createFailed": return "Não foi possível criar o item no Nextcloud";
            case "moveFailed": return "Não foi possível mover ou mudar o nome do item Nextcloud"; case "editorFailed": return "Não foi possível abrir a sessão de coedição";
            case "invalidRequest": return "Pedido inválido"; case "configInvalid": return "Configuração do conector do servidor inválida";
            case "interrupted": return "Operação interrompida"; case "unavailable": return "O conector Nextcloud está indisponível, mas o Zimbra continua operacional";
            case "internalError": return "Erro interno de ligação ao Nextcloud"; case "forbidden": return "O Nextcloud recusou esta operação";
            default: return "Não foi possível concluir a operação";
        }
    }

    private static String portugueseBrazil(String key) {
        switch (key) {
            case "unknownError": return "Erro desconhecido"; case "credentialsInvalid": return "Usuário ou senha de aplicativo do Nextcloud incorretos";
            case "sessionExpired": return "A sessão do Zimbra expirou"; case "sessionInvalid": return "Sessão do Zimbra inválida";
            case "configureFirst": return "Configure primeiro sua conta Nextcloud"; case "endpointUnknown": return "Ponto de acesso desconhecido";
            case "requestDenied": return "Solicitação recusada"; case "securityInvalid": return "Token de segurança inválido";
            case "tooLarge": return "O conteúdo excede o tamanho máximo permitido"; case "conflict": return "Já existe um item com este nome ou a pasta superior não existe";
            case "notFound": return "Item do Nextcloud não encontrado"; case "expirationInvalid": return "Data de vencimento inválida";
            case "appPasswordRequired": return "A senha de aplicativo do Nextcloud é obrigatória"; case "usernameInvalid": return "Usuário do Nextcloud inválido";
            case "managedRestriction": return "Esta operação é gerenciada pelo administrador do Nextcloud"; case "provisioningDisabled": return "O provisionamento automático do Nextcloud não está ativado";
            case "alreadyConnected": return "Esta conta Zimbra já tem uma conexão Nextcloud"; case "accountExists": return "Já existe uma conta Nextcloud com este usuário";
            case "maxAccounts": return "No máximo três contas Nextcloud podem ser conectadas"; case "accountUnknown": return "Conta Nextcloud desconhecida";
            case "invalidServerAddress": return "O endereço do servidor Nextcloud é inválido ou não permitido"; case "unsupportedFormat": return "Este formato de arquivo não é compatível";
            case "folderNotEditable": return "Uma pasta não pode ser aberta no editor"; case "thumbnailUnavailable": return "Miniatura do Nextcloud indisponível";
            case "sharingFailed": return "Não foi possível criar o link público do Nextcloud"; case "trashFailed": return "A operação da lixeira do Nextcloud falhou";
            case "searchFailed": return "A pesquisa do Nextcloud falhou"; case "downloadFailed": return "Não foi possível baixar o arquivo do Nextcloud";
            case "uploadFailed": return "Não foi possível enviar o arquivo para o Nextcloud"; case "createFailed": return "Não foi possível criar o item no Nextcloud";
            case "moveFailed": return "Não foi possível mover ou renomear o item do Nextcloud"; case "editorFailed": return "Não foi possível abrir a sessão de coedição";
            case "invalidRequest": return "Solicitação inválida"; case "configInvalid": return "Configuração do conector do servidor inválida";
            case "interrupted": return "Operação interrompida"; case "unavailable": return "O conector Nextcloud está indisponível, mas o Zimbra continua operacional";
            case "internalError": return "Erro interno de conexão com o Nextcloud"; case "forbidden": return "O Nextcloud recusou esta operação";
            default: return "Não foi possível concluir a operação";
        }
    }

    private static String hindi(String key) {
        switch (key) {
            case "unknownError": return "अज्ञात त्रुटि"; case "credentialsInvalid": return "Nextcloud उपयोगकर्ता नाम या ऐप पासवर्ड गलत है";
            case "sessionExpired": return "Zimbra सत्र समाप्त हो गया"; case "sessionInvalid": return "अमान्य Zimbra सत्र";
            case "configureFirst": return "पहले अपना Nextcloud खाता कॉन्फ़िगर करें"; case "endpointUnknown": return "अज्ञात एंडपॉइंट";
            case "requestDenied": return "अनुरोध अस्वीकृत"; case "securityInvalid": return "अमान्य सुरक्षा टोकन";
            case "tooLarge": return "सामग्री अनुमत अधिकतम आकार से बड़ी है"; case "conflict": return "इस नाम का आइटम पहले से मौजूद है या मूल फ़ोल्डर नहीं मिला";
            case "notFound": return "Nextcloud आइटम नहीं मिला"; case "expirationInvalid": return "समाप्ति तिथि अमान्य है";
            case "appPasswordRequired": return "Nextcloud ऐप पासवर्ड आवश्यक है"; case "usernameInvalid": return "Nextcloud उपयोगकर्ता नाम अमान्य है";
            case "managedRestriction": return "यह कार्रवाई Nextcloud व्यवस्थापक द्वारा प्रबंधित है"; case "provisioningDisabled": return "स्वचालित Nextcloud प्रोविज़निंग सक्षम नहीं है";
            case "alreadyConnected": return "इस Zimbra खाते में पहले से Nextcloud कनेक्शन है"; case "accountExists": return "इस उपयोगकर्ता नाम का Nextcloud खाता पहले से मौजूद है";
            case "maxAccounts": return "अधिकतम तीन Nextcloud खाते जोड़े जा सकते हैं"; case "accountUnknown": return "अज्ञात Nextcloud खाता";
            case "invalidServerAddress": return "Nextcloud सर्वर पता अमान्य या अनुमत नहीं है"; case "unsupportedFormat": return "यह फ़ाइल प्रारूप समर्थित नहीं है";
            case "folderNotEditable": return "फ़ोल्डर को संपादक में नहीं खोला जा सकता"; case "thumbnailUnavailable": return "Nextcloud थंबनेल उपलब्ध नहीं है";
            case "sharingFailed": return "Nextcloud सार्वजनिक लिंक नहीं बनाया जा सका"; case "trashFailed": return "Nextcloud ट्रैश कार्रवाई विफल रही";
            case "searchFailed": return "Nextcloud खोज विफल रही"; case "downloadFailed": return "Nextcloud फ़ाइल डाउनलोड नहीं हो सकी";
            case "uploadFailed": return "फ़ाइल Nextcloud पर अपलोड नहीं हो सकी"; case "createFailed": return "Nextcloud में आइटम नहीं बनाया जा सका";
            case "moveFailed": return "Nextcloud आइटम को स्थानांतरित या पुनर्नामित नहीं किया जा सका"; case "editorFailed": return "सह-संपादन सत्र नहीं खुल सका";
            case "invalidRequest": return "अमान्य अनुरोध"; case "configInvalid": return "सर्वर कनेक्टर कॉन्फ़िगरेशन अमान्य है";
            case "interrupted": return "कार्रवाई बाधित हुई"; case "unavailable": return "Nextcloud कनेक्टर उपलब्ध नहीं है, लेकिन Zimbra चालू है";
            case "internalError": return "Nextcloud कनेक्शन में आंतरिक त्रुटि"; case "forbidden": return "Nextcloud ने यह कार्रवाई अस्वीकार कर दी";
            default: return "कार्रवाई पूरी नहीं की जा सकी";
        }
    }

    private static String malay(String key) {
        switch (key) {
            case "unknownError": return "Ralat tidak diketahui"; case "credentialsInvalid": return "Nama pengguna atau kata laluan aplikasi Nextcloud tidak betul";
            case "sessionExpired": return "Sesi Zimbra telah tamat"; case "sessionInvalid": return "Sesi Zimbra tidak sah";
            case "configureFirst": return "Konfigurasikan akaun Nextcloud anda dahulu"; case "endpointUnknown": return "Titik akhir tidak diketahui";
            case "requestDenied": return "Permintaan ditolak"; case "securityInvalid": return "Token keselamatan tidak sah";
            case "tooLarge": return "Kandungan melebihi saiz maksimum yang dibenarkan"; case "conflict": return "Item dengan nama ini sudah wujud atau folder induk tiada";
            case "notFound": return "Item Nextcloud tidak ditemui"; case "expirationInvalid": return "Tarikh luput tidak sah";
            case "appPasswordRequired": return "Kata laluan aplikasi Nextcloud diperlukan"; case "usernameInvalid": return "Nama pengguna Nextcloud tidak sah";
            case "managedRestriction": return "Operasi ini diurus oleh pentadbir Nextcloud"; case "provisioningDisabled": return "Peruntukan automatik Nextcloud tidak didayakan";
            case "alreadyConnected": return "Akaun Zimbra ini sudah mempunyai sambungan Nextcloud"; case "accountExists": return "Akaun Nextcloud dengan nama pengguna ini sudah wujud";
            case "maxAccounts": return "Maksimum tiga akaun Nextcloud boleh disambungkan"; case "accountUnknown": return "Akaun Nextcloud tidak diketahui";
            case "invalidServerAddress": return "Alamat pelayan Nextcloud tidak sah atau tidak dibenarkan"; case "unsupportedFormat": return "Format fail ini tidak disokong";
            case "folderNotEditable": return "Folder tidak boleh dibuka dalam penyunting"; case "thumbnailUnavailable": return "Imej kecil Nextcloud tidak tersedia";
            case "sharingFailed": return "Pautan awam Nextcloud tidak dapat dicipta"; case "trashFailed": return "Operasi tong sampah Nextcloud gagal";
            case "searchFailed": return "Carian Nextcloud gagal"; case "downloadFailed": return "Fail Nextcloud tidak dapat dimuat turun";
            case "uploadFailed": return "Fail tidak dapat dimuat naik ke Nextcloud"; case "createFailed": return "Item tidak dapat dicipta dalam Nextcloud";
            case "moveFailed": return "Item Nextcloud tidak dapat dipindah atau dinamakan semula"; case "editorFailed": return "Sesi penyuntingan bersama tidak dapat dibuka";
            case "invalidRequest": return "Permintaan tidak sah"; case "configInvalid": return "Konfigurasi penyambung pelayan tidak sah";
            case "interrupted": return "Operasi terganggu"; case "unavailable": return "Penyambung Nextcloud tidak tersedia, tetapi Zimbra kekal beroperasi";
            case "internalError": return "Ralat dalaman sambungan Nextcloud"; case "forbidden": return "Nextcloud menolak operasi ini";
            default: return "Operasi tidak dapat diselesaikan";
        }
    }

    private static String russian(String key) {
        switch (key) {
            case "unknownError": return "Неизвестная ошибка"; case "credentialsInvalid": return "Неверное имя пользователя или пароль приложения Nextcloud";
            case "sessionExpired": return "Сеанс Zimbra истёк"; case "sessionInvalid": return "Недопустимый сеанс Zimbra";
            case "configureFirst": return "Сначала настройте учётную запись Nextcloud"; case "endpointUnknown": return "Неизвестная конечная точка";
            case "requestDenied": return "Запрос отклонён"; case "securityInvalid": return "Недопустимый токен безопасности";
            case "tooLarge": return "Содержимое превышает максимально допустимый размер"; case "conflict": return "Элемент с таким именем уже существует или родительская папка отсутствует";
            case "notFound": return "Элемент Nextcloud не найден"; case "expirationInvalid": return "Недопустимая дата окончания";
            case "appPasswordRequired": return "Требуется пароль приложения Nextcloud"; case "usernameInvalid": return "Недопустимое имя пользователя Nextcloud";
            case "managedRestriction": return "Эта операция управляется администратором Nextcloud"; case "provisioningDisabled": return "Автоматическое создание учётных записей Nextcloud не включено";
            case "alreadyConnected": return "К этой учётной записи Zimbra уже подключён Nextcloud"; case "accountExists": return "Учётная запись Nextcloud с таким именем уже существует";
            case "maxAccounts": return "Можно подключить не более трёх учётных записей Nextcloud"; case "accountUnknown": return "Неизвестная учётная запись Nextcloud";
            case "invalidServerAddress": return "Адрес сервера недопустим или не разрешён"; case "unsupportedFormat": return "Этот формат файла не поддерживается";
            case "folderNotEditable": return "Папку нельзя открыть в редакторе"; case "thumbnailUnavailable": return "Миниатюра Nextcloud недоступна";
            case "sharingFailed": return "Не удалось создать общедоступную ссылку Nextcloud"; case "trashFailed": return "Операция с корзиной Nextcloud не выполнена";
            case "searchFailed": return "Поиск в Nextcloud не выполнен"; case "downloadFailed": return "Не удалось скачать файл Nextcloud";
            case "uploadFailed": return "Не удалось загрузить файл в Nextcloud"; case "createFailed": return "Не удалось создать элемент в Nextcloud";
            case "moveFailed": return "Не удалось переместить или переименовать элемент Nextcloud"; case "editorFailed": return "Не удалось открыть сеанс совместного редактирования";
            case "invalidRequest": return "Недопустимый запрос"; case "configInvalid": return "Недопустимая конфигурация сервера коннектора";
            case "interrupted": return "Операция прервана"; case "unavailable": return "Коннектор Nextcloud недоступен, но Zimbra продолжает работать";
            case "internalError": return "Внутренняя ошибка подключения Nextcloud"; case "forbidden": return "Nextcloud отклонил эту операцию";
            default: return "Не удалось выполнить операцию";
        }
    }
}
