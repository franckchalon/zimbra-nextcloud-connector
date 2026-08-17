package fr.franckchalon.zimbra.nextcloud;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ServerUnitTest {
    public static void main(String[] args) throws Exception {
        testVersion();
        testJson();
        testPaths();
        testCrypto();
        testJwt();
        testCredentialStore();
        testMultipleProfilesAndMigration();
        testConfigAllowList();
        testOfficeProviderConfiguration();
        testManagedAccountConfiguration();
        testManagedProvisioningHelpers();
        testManagedProvisioningWorkflow();
        testServerI18n();
        testEditorLocales();
        testOpenDocumentTemplates();
        testLocalizedDocumentTemplates();
        testOfficeOcsEnvelope();
        testPublicShareForm();
        testWebDavParsing();
        testVersionDavWorkflow();
        testTalkApis();
        testTrashParsing();
        testWebDavSearchBody();
        testWebDavZipDownload();
        testBulkDestinations();
        testRequestLimiter();
        testFailSafeConstruction();
        System.out.println("ServerUnitTest: OK");
    }

    private static void testVersion() {
        check("3.1.23".equals(Version.VALUE), "Version serveur 3.1.23");
        check(Version.USER_AGENT.contains(Version.VALUE), "Version présente dans le User-Agent");
    }

    private static void testJson() {
        LinkedHashMap<String, Object> source = new LinkedHashMap<>();
        source.put("text", "écriture \"test\"\nligne");
        source.put("number", 42L);
        source.put("enabled", true);
        Map<String, Object> parsed = Json.parseObject(Json.stringify(source));
        check(source.get("text").equals(parsed.get("text")), "JSON Unicode");
        check(Json.longValue(parsed, "number", 0) == 42L, "JSON nombre");
    }

    private static void testPaths() {
        check("/Dossier/Fichier.docx".equals(PathUtil.normalize("//Dossier//Fichier.docx")), "Normalisation chemin");
        check("docx".equals(PathUtil.extension("/Dossier/Fichier.docx")), "Extension");
        check("/Dossier".equals(PathUtil.parent("/Dossier/Fichier.docx")), "Dossier parent");
        check("/".equals(PathUtil.parent("/Fichier.docx")), "Dossier parent racine");
        expectFailure(() -> PathUtil.normalize("/Dossier/../secret"), "Traversal refusé");
        expectFailure(() -> PathUtil.validateName("a/b"), "Slash refusé");
    }

    private static void testCrypto() throws Exception {
        byte[] key = Crypto.randomBytes(32);
        String encrypted = Crypto.encrypt(key, "account-1", "mot de passe secret");
        check("mot de passe secret".equals(Crypto.decrypt(key, "account-1", encrypted)), "AES-GCM");
        try {
            Crypto.decrypt(key, "account-2", encrypted);
            throw new AssertionError("L’AAD doit empêcher le déchiffrement pour un autre compte");
        } catch (GeneralSecurityException expected) {}
    }

    private static void testJwt() throws Exception {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", "account-1");
        payload.put("exp", Instant.now().getEpochSecond() + 60);
        String token = Jwt.sign(payload, "un-secret-jwt-suffisamment-long-pour-le-test");
        check("account-1".equals(Jwt.verify(token, "un-secret-jwt-suffisamment-long-pour-le-test").get("sub")), "JWT valide");
        try {
            Jwt.verify(token, "mauvais-secret");
            throw new AssertionError("Une mauvaise signature JWT doit être refusée");
        } catch (GeneralSecurityException expected) {}
    }

    private static void testCredentialStore() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-store-test-");
        try {
            CredentialStore store = new CredentialStore(root);
            Profile profile = new Profile("https://cloud.example.com", "franck", "application-password", 1L);
            store.save("zimbra-account", profile);
            Profile loaded = store.load("zimbra-account").orElseThrow();
            check("application-password".equals(loaded.appPassword), "Stockage chiffré");
            String encryptedAtRest = Files.readString(root.resolve("profiles").resolve(Crypto.sha256Hex("zimbra-account") + ".enc"));
            check(!encryptedAtRest.contains("application-password"), "Secret absent en clair");
            store.delete("zimbra-account");
            check(store.load("zimbra-account").isEmpty(), "Suppression profil");
        } finally {
            deleteTree(root);
        }
    }

    private static void testMultipleProfilesAndMigration() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-multiple-profiles-test-");
        try {
            CredentialStore store = new CredentialStore(root);
            Profile first = new Profile("https://one.example.com", "user-one", "secret-one", 1L)
                .withIdentity("", "Travail", false);
            Profile second = new Profile("https://two.example.com", "user-two", "secret-two", 2L)
                .withIdentity("", "Personnel", false);
            Profile third = new Profile("https://three.example.com", "user-three", "secret-three", 3L)
                .withIdentity("", "Archives", false);
            store.saveProfiles("account", ProfileSet.empty().upsert(first, true).upsert(second, true).upsert(third, false)
                .withTalkEnabled(second.id, true));
            ProfileSet profiles = store.loadProfiles("account").orElseThrow();
            check(profiles.profiles.size() == 3, "Trois comptes Nextcloud chiffrés");
            check(second.id.equals(profiles.activeProfileId), "Compte Nextcloud actif conservé");
            check(profiles.talkEnabled(second.id) && !profiles.talkEnabled(first.id),
                "Activation du Chat conservée séparément pour chaque compte Nextcloud");
            check("secret-one".equals(store.load("account", first.id).orElseThrow().appPassword), "Sélection sécurisée par identifiant");
            store.select("account", third.id);
            check(third.id.equals(store.loadProfiles("account").orElseThrow().activeProfileId), "Changement de compte persistant");
            store.delete("account", third.id);
            check(store.loadProfiles("account").orElseThrow().profiles.size() == 2, "Retrait sans supprimer les autres comptes");
            expectFailure(() -> store.saveProfiles("account", store.loadProfiles("account").orElseThrow()
                .upsert(third, false).upsert(new Profile("https://four.example.com", "user-four", "secret-four", 4L), false)),
                "Quatrième compte refusé");

            LinkedHashMap<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("nextcloudUrl", "https://legacy.example.com");
            legacy.put("username", "legacy-user");
            legacy.put("appPassword", "legacy-secret");
            legacy.put("updatedAt", 4L);
            ProfileSet migrated = ProfileSet.fromStorageMap(legacy);
            check(migrated.profiles.size() == 1 && "legacy-secret".equals(migrated.active().orElseThrow().appPassword),
                "Migration transparente du profil unique 2.1");
            check(!migrated.anyTalkEnabled(), "Chat désactivé par défaut lors de la migration d’un ancien profil");

            LinkedHashMap<String, Object> globalTalk = new LinkedHashMap<>();
            globalTalk.put("version", 4L);
            globalTalk.put("activeProfileId", second.id);
            globalTalk.put("talkEnabled", true);
            globalTalk.put("profiles", List.of(first.toStorageMap(), second.toStorageMap()));
            ProfileSet migratedTalk = ProfileSet.fromStorageMap(globalTalk);
            check(migratedTalk.talkEnabled(second.id) && !migratedTalk.talkEnabled(first.id),
                "Migration 3.1.1 limitée au compte Nextcloud alors actif");
        } finally {
            deleteTree(root);
        }
    }

    private static void testConfigAllowList() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-config-test-");
        Path file = root.resolve("config.properties");
        Path templates = root.resolve("templates");
        Files.createDirectories(templates);
        Files.write(templates.resolve("Contrat.odt"), new byte[]{1, 2, 3});
        String properties = "storage.dir=" + root.resolve("data") + "\n"
            + "storage.shared=true\n"
            + "templates.dir=" + templates + "\n"
            + "ui.remote_backgrounds=true\n"
            + "files.upload_chunk_bytes=5242880\n"
            + "limits.max_concurrent_requests=12\n"
            + "limits.max_requests_per_account_minute=120\n"
            + "limits.max_directory_response_items=1200\n"
            + "ui.default_language=pt-BR\n"
            + "zimbra.public_url=https://mail.example.com\n"
            + "nextcloud.allow_http=false\n"
            + "nextcloud.block_private_networks=true\n"
            + "nextcloud.private_hosts=cloud.example.com\n"
            + "office.provider=eurooffice\n"
            + "office.public_url=https://eurooffice.example.com\n"
            + "office.download_hosts=eurooffice.example.com,10.20.22.242\n"
            + "office.security_mode=jwt\n"
            + "office.jwt_secret=01234567890123456789012345678901\n"
            + "office.jwt_header=Authorization\n"
            + "security.ticket_secret=0123456789012345678901234567890123456789012345678901234567890123\n";
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        String previous = System.getProperty("nextcloud.zimlet.config");
        System.setProperty("nextcloud.zimlet.config", file.toString());
        try {
            AppConfig config = AppConfig.load();
            check("pt-BR".equals(config.defaultLanguage), "Variante linguistique régionale conservée");
            check("https://cloud.example.com".equals(config.validateNextcloudUrl("https://cloud.example.com/")), "Hôte privé explicitement autorisé");
            check("https://1.1.1.1".equals(config.validateNextcloudUrl("https://1.1.1.1")), "Serveur Nextcloud public libre");
            expectFailure(() -> config.validateNextcloudUrl("https://127.0.0.1"), "SSRF Nextcloud refusé");
            check("eurooffice".equals(config.officeProvider), "Fournisseur Euro-Office sélectionné");
            check("eurooffice".equals(config.officeConnectorAppId), "Connecteur Nextcloud Euro-Office sélectionné");
            check(config.officeJwtEnabled(), "JWT Euro-Office activé");
            check(config.sharedStorage && config.remoteBackgroundsAllowed, "Options de stockage partagé et d’arrière-plan");
            check(config.uploadChunkBytes == 5242880L && config.maxConcurrentRequests == 12
                && config.maxRequestsPerMinute == 120 && config.maxDirectoryResponseItems == 1200,
                "Limites de transfert et de requêtes chargées");
            check(new DocumentTemplateService(config).list().stream()
                .anyMatch(template -> "Contrat.odt".equals(template.get("id"))), "Modèle personnalisé détecté");
            expectFailure(() -> config.validateOfficeDownloadUrl("https://evil.example.net/file"), "SSRF Euro-Office refusé");
            check("https://eurooffice.example.com".equals(config.validateOfficeEditorUrl("https://eurooffice.example.com/")),
                "Serveur Euro-Office Nextcloud conforme");
            expectFailure(() -> config.validateOfficeEditorUrl("https://evil.example.net"),
                "Serveur Euro-Office Nextcloud différent refusé");

            String profileSecret = "profil-jwt-012345678901234567890123456789";
            OfficeProfile custom = OfficeProfile.custom(config, "onlyoffice", "https://1.1.1.1",
                "jwt", "X-OnlyOffice-Jwt", profileSecret);
            Profile customProfile = new Profile("", "Second Cloud", "https://cloud.example.com", "user",
                "app-password", 5L, false, custom);
            OfficeSettings resolved = customProfile.office.resolve(config);
            check("custom".equals(resolved.mode) && "onlyoffice".equals(resolved.connectorAppId),
                "Serveur d’édition propre au profil");
            check("X-OnlyOffice-Jwt".equals(resolved.jwtHeader) && profileSecret.equals(resolved.jwtSecret),
                "Paramètres JWT propres au profil");
            Profile restored = Profile.fromStorageMap(customProfile.toStorageMap());
            check(profileSecret.equals(restored.office.jwtSecret), "Migration du réglage d’édition chiffrable");
            CredentialStore customStore = new CredentialStore(root.resolve("custom-store"));
            customStore.save("office-account", customProfile);
            String encryptedProfile = Files.readString(root.resolve("custom-store/profiles")
                .resolve(Crypto.sha256Hex("office-account") + ".enc"));
            check(!encryptedProfile.contains(profileSecret) && !encryptedProfile.contains("X-OnlyOffice-Jwt"),
                "Secret et configuration bureautique absents en clair");
            expectFailure(() -> OfficeProfile.custom(config, "onlyoffice", "https://1.1.1.1",
                "jwt", "Authorization", "trop-court"), "Secret JWT personnalisé trop court refusé");
        } finally {
            if (previous == null) System.clearProperty("nextcloud.zimlet.config");
            else System.setProperty("nextcloud.zimlet.config", previous);
            deleteTree(root);
        }
    }

    private static void testOfficeProviderConfiguration() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-office-provider-test-");
        Path file = root.resolve("config.properties");
        String properties = "storage.dir=" + root.resolve("data") + "\n"
            + "zimbra.public_url=https://mail.example.com\n"
            + "office.provider=onlyoffice\n"
            + "office.public_url=https://office.example.com\n"
            + "office.download_hosts=office.example.com\n"
            + "office.security_mode=none\n"
            + "office.jwt_header=Authorization\n"
            + "security.ticket_secret=0123456789012345678901234567890123456789012345678901234567890123\n";
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        String previous = System.getProperty("nextcloud.zimlet.config");
        System.setProperty("nextcloud.zimlet.config", file.toString());
        try {
            AppConfig config = AppConfig.load();
            check("onlyoffice".equals(config.officeConnectorAppId), "Connecteur Nextcloud ONLYOFFICE sélectionné");
            check(!config.officeJwtEnabled(), "Mode de test sans JWT accepté");
        } finally {
            if (previous == null) System.clearProperty("nextcloud.zimlet.config");
            else System.setProperty("nextcloud.zimlet.config", previous);
            deleteTree(root);
        }
    }

    private static void testManagedAccountConfiguration() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-managed-account-test-");
        Path file = root.resolve("config.properties");
        String properties = "storage.dir=" + root.resolve("data") + "\n"
            + "zimbra.public_url=https://mail.example.com\n"
            + "ui.default_language=de\n"
            + "nextcloud.account_mode=managed\n"
            + "managed.nextcloud_url=https://cloud.example.com\n"
            + "managed.admin_username=zimlet-provisioner\n"
            + "managed.admin_app_password=service-app-password\n"
            + "managed.group=zimbra-users\n"
            + "managed.quota=10 GB\n"
            + "managed.language=fr\n"
            + "office.provider=onlyoffice\n"
            + "office.public_url=https://office.example.com\n"
            + "office.download_hosts=office.example.com\n"
            + "office.security_mode=none\n"
            + "office.jwt_header=Authorization\n"
            + "security.ticket_secret=0123456789012345678901234567890123456789012345678901234567890123\n";
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        String previous = System.getProperty("nextcloud.zimlet.config");
        System.setProperty("nextcloud.zimlet.config", file.toString());
        try {
            AppConfig config = AppConfig.load();
            check(config.managedAccountsEnabled(), "Mode de comptes Nextcloud gérés activé");
            check("https://cloud.example.com".equals(config.managedNextcloudUrl), "Serveur Nextcloud géré");
            check("zimlet-provisioner".equals(config.managedAdminUsername), "Compte de service Nextcloud");
            check("zimbra-users".equals(config.managedGroup), "Groupe Nextcloud géré");
            check("10 GB".equals(config.managedQuota), "Quota Nextcloud géré");
            check("de".equals(config.defaultLanguage), "Langue serveur par défaut");
        } finally {
            if (previous == null) System.clearProperty("nextcloud.zimlet.config");
            else System.setProperty("nextcloud.zimlet.config", previous);
            deleteTree(root);
        }
    }

    private static void testManagedProvisioningHelpers() {
        check("user@example.com".equals(NextcloudProvisioningService.managedUserId(" User@Example.COM ")),
            "Adresse Zimbra normalisée comme identifiant Nextcloud");
        expectFailure(() -> NextcloudProvisioningService.managedUserId("identifiant-sans-domaine"),
            "Adresse Zimbra invalide refusée");

        String firstPassword = NextcloudProvisioningService.generateInitialPassword();
        String secondPassword = NextcloudProvisioningService.generateInitialPassword();
        check(firstPassword.startsWith("Nc!7-") && firstPassword.length() >= 45,
            "Mot de passe initial robuste");
        check(!firstPassword.equals(secondPassword), "Mots de passe initiaux uniques");

        String form = NextcloudProvisioningService.createUserForm(
            "user@example.com", "user@example.com", "secret & solide", "zimbra users", "10 GB", "fr"
        );
        check(form.contains("userid=user%40example.com"), "Identifiant OCS encodé");
        check(form.contains("password=secret+%26+solide"), "Mot de passe OCS encodé");
        check(form.contains("groups%5B%5D=zimbra+users"), "Groupe OCS encodé");
        check(form.contains("quota=10+GB"), "Quota OCS encodé");
        check(form.contains("language=fr"), "Langue OCS encodée");
    }

    private static void testManagedProvisioningWorkflow() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-managed-workflow-test-");
        Path file = root.resolve("config.properties");
        String properties = "storage.dir=" + root.resolve("data") + "\n"
            + "zimbra.public_url=https://mail.example.com\n"
            + "nextcloud.account_mode=managed\n"
            + "managed.nextcloud_url=https://cloud.example.com\n"
            + "managed.admin_username=zimlet-provisioner\n"
            + "managed.admin_app_password=service-app-password\n"
            + "managed.group=zimbra-users\n"
            + "managed.quota=10 GB\n"
            + "managed.language=fr\n"
            + "office.provider=onlyoffice\n"
            + "office.public_url=https://office.example.com\n"
            + "office.download_hosts=office.example.com\n"
            + "office.security_mode=none\n"
            + "office.jwt_header=Authorization\n"
            + "security.ticket_secret=0123456789012345678901234567890123456789012345678901234567890123\n";
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        String previous = System.getProperty("nextcloud.zimlet.config");
        System.setProperty("nextcloud.zimlet.config", file.toString());
        try {
            AppConfig config = AppConfig.load();
            CredentialStore store = new CredentialStore(config.storageDirectory);
            String created = ocs(100, "ok", "OK", "{}");
            String appPassword = ocs(200, "ok", "OK", "{\"apppassword\":\"managed-app-token\"}");
            FakeProvisioningTransport successTransport = new FakeProvisioningTransport(created, appPassword);
            Profile[] verified = new Profile[1];
            NextcloudProvisioningService successService = new NextcloudProvisioningService(
                config, store, successTransport, profile -> verified[0] = profile
            );
            AccountContext account = new AccountContext("zimbra-managed-success", "User@Example.COM");
            NextcloudProvisioningService.ActivationResult activation = successService.activate(account);

            check(successTransport.requests.size() == 2, "Mode géré : création puis mot de passe d’application");
            HttpRequest createRequest = successTransport.requests.get(0);
            HttpRequest passwordRequest = successTransport.requests.get(1);
            check("POST".equals(createRequest.method()) && createRequest.uri().getPath().endsWith("/ocs/v1.php/cloud/users"),
                "Mode géré : endpoint de création OCS");
            check(createRequest.headers().firstValue("OCS-APIRequest").orElse("").equals("true"),
                "Mode géré : en-tête OCS présent");
            String adminBasic = decodeBasic(createRequest);
            check("zimlet-provisioner:service-app-password".equals(adminBasic),
                "Mode géré : authentification du compte de service");
            check("GET".equals(passwordRequest.method()) && passwordRequest.uri().getPath().endsWith("/ocs/v2.php/core/getapppassword"),
                "Mode géré : endpoint de mot de passe d’application");
            String userBasic = decodeBasic(passwordRequest);
            check(userBasic.startsWith("user@example.com:Nc!7-"),
                "Mode géré : le nouveau compte demande son propre mot de passe d’application");
            check(verified[0] != null && "managed-app-token".equals(verified[0].appPassword) && verified[0].managed,
                "Mode géré : WebDAV est vérifié avec le jeton applicatif");
            Profile stored = store.load(account.id).orElseThrow();
            check("user@example.com".equals(stored.username) && "managed-app-token".equals(stored.appPassword),
                "Mode géré : profil connecté enregistré");
            String encrypted = Files.readString(root.resolve("data/profiles").resolve(Crypto.sha256Hex(account.id) + ".enc"));
            check(!encrypted.contains("managed-app-token") && !encrypted.contains(activation.initialPassword),
                "Mode géré : secrets absents en clair du stockage");
            Map<String, Object> response = activation.toMap(new LinkedHashMap<>());
            check(activation.initialPassword.equals(response.get("initialPassword"))
                    && Boolean.TRUE.equals(response.get("passwordShownOnce")),
                "Mode géré : mot de passe initial livré uniquement dans la réponse d’activation");

            int successRequestCount = successTransport.requests.size();
            try {
                successService.activate(account);
                throw new AssertionError("Une seconde activation doit être refusée");
            } catch (HttpError expected) {
                check(expected.status == 409, "Mode géré : seconde activation refusée avec 409");
            }
            check(successTransport.requests.size() == successRequestCount,
                "Mode géré : aucun appel Nextcloud lors d’une seconde activation");

            FakeProvisioningTransport rollbackTransport = new FakeProvisioningTransport(
                created,
                ocs(200, "ok", "OK", "{}"),
                created
            );
            NextcloudProvisioningService rollbackService = new NextcloudProvisioningService(
                config, store, rollbackTransport, profile -> { throw new AssertionError("WebDAV ne doit pas être appelé sans jeton"); }
            );
            AccountContext rollbackAccount = new AccountContext("zimbra-managed-rollback", "rollback@example.com");
            expectFailure(() -> rollbackService.activate(rollbackAccount),
                "Mode géré : réponse sans mot de passe d’application refusée");
            check(rollbackTransport.requests.size() == 3
                    && "DELETE".equals(rollbackTransport.requests.get(2).method())
                    && rollbackTransport.requests.get(2).uri().getRawPath().contains("rollback%40example.com"),
                "Mode géré : compte incomplet supprimé automatiquement");
            check(store.load(rollbackAccount.id).isEmpty(),
                "Mode géré : aucun profil conservé après retour arrière");

            FakeProvisioningTransport existingTransport = new FakeProvisioningTransport(
                ocs(102, "failure", "User already exists", "{}")
            );
            NextcloudProvisioningService existingService = new NextcloudProvisioningService(
                config, store, existingTransport, profile -> {}
            );
            try {
                existingService.activate(new AccountContext("zimbra-managed-existing", "existing@example.com"));
                throw new AssertionError("Un utilisateur Nextcloud existant doit être refusé");
            } catch (HttpError expected) {
                check(expected.status == 409, "Mode géré : compte Nextcloud existant refusé avec 409");
            }
            check(existingTransport.requests.size() == 1,
                "Mode géré : un compte préexistant n’est jamais supprimé ni réinitialisé");
        } finally {
            if (previous == null) System.clearProperty("nextcloud.zimlet.config");
            else System.setProperty("nextcloud.zimlet.config", previous);
            deleteTree(root);
        }
    }

    private static String ocs(long code, String status, String message, String data) {
        return "{\"ocs\":{\"meta\":{\"status\":\"" + status + "\",\"statuscode\":" + code
            + ",\"message\":\"" + message + "\"},\"data\":" + data + "}}";
    }

    private static String decodeBasic(HttpRequest request) {
        String authorization = request.headers().firstValue("Authorization").orElse("");
        check(authorization.startsWith("Basic "), "Authentification HTTP Basic attendue");
        return new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
    }

    private static void testServerI18n() {
        String source = "Identifiant ou mot de passe d’application Nextcloud incorrect";
        for (String language : new String[]{"en-US", "es-AR", "it", "de", "pt-BR", "hi-IN", "ms-MY", "ru-RU"}) {
            ServerI18n.use(language);
            String localized = ServerI18n.localize(source);
            check(!localized.equals(source) && !localized.contains("Identifiant"),
                "Erreur serveur traduite en " + language);
            check(!ServerI18n.text("editorFailed").isBlank(), "Erreur éditeur traduite en " + language);
            ServerI18n.clear();
        }
        ServerI18n.use("fr");
        check(source.equals(ServerI18n.localize(source)), "Message serveur français conservé");
        ServerI18n.clear();
        ServerI18n.use("pt-BR");
        check(ServerI18n.text("credentialsInvalid").contains("senha de aplicativo"), "Erreurs serveur en portugais du Brésil");
        ServerI18n.clear();
    }

    private static void testEditorLocales() {
        check("pt-PT".equals(EditorLocale.from("pt_PT").editorLanguage), "Interface éditeur portugais Portugal");
        check("pt".equals(EditorLocale.from("pt-BR").editorLanguage), "Interface éditeur portugais Brésil");
        check("es".equals(EditorLocale.from("es-AR").editorLanguage), "Interface éditeur espagnol Argentine");
        check("en".equals(EditorLocale.from("hi-IN").editorLanguage), "Repli éditeur anglais pour hindi non pris en charge");
        check("ms".equals(EditorLocale.from("ms-MY").editorLanguage), "Interface éditeur malais");
        check("ru".equals(EditorLocale.from("ru-RU").editorLanguage), "Interface éditeur russe");

        LinkedHashMap<String, Object> editor = new LinkedHashMap<>();
        LinkedHashMap<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("lang", "en");
        editorConfig.put("region", "en-US");
        editor.put("editorConfig", editorConfig);
        OnlyOfficeService.applyEditorLocale(editor, "pt-BR");
        check("pt".equals(editorConfig.get("lang")) && "pt-BR".equals(editorConfig.get("region")),
            "Locale imposée à ONLYOFFICE ou Euro-Office");
    }

    private static void testOpenDocumentTemplates() throws Exception {
        for (String extension : new String[]{"odt", "ods", "odp"}) {
            try (java.io.InputStream resource = ServerUnitTest.class.getResourceAsStream("/templates/blank." + extension)) {
                check(resource != null, "Modèle OpenDocument " + extension + " présent");
                boolean hasMimeType = false;
                boolean hasContent = false;
                try (ZipInputStream archive = new ZipInputStream(resource)) {
                    ZipEntry entry;
                    while ((entry = archive.getNextEntry()) != null) {
                        if ("mimetype".equals(entry.getName())) hasMimeType = true;
                        if ("content.xml".equals(entry.getName())) hasContent = true;
                    }
                }
                check(hasMimeType && hasContent, "Modèle OpenDocument " + extension + " valide");
            }
        }
    }

    private static void testLocalizedDocumentTemplates() throws Exception {
        try (java.io.InputStream resource = ServerUnitTest.class.getResourceAsStream("/templates/blank.odt")) {
            byte[] localized = DocumentTemplateLocalizer.localize(resource.readAllBytes(), "odt", "fr-FR");
            String xml = zipText(localized);
            check(xml.contains("fo:language=\"fr\"") && xml.contains("fo:country=\"FR\""),
                "Langue interne du nouveau document ODT localisée");
            check(!xml.contains("fo:language=\"en\""), "Langue anglaise retirée du modèle ODT français");
        }
        try (java.io.InputStream resource = ServerUnitTest.class.getResourceAsStream("/templates/blank.docx")) {
            byte[] localized = DocumentTemplateLocalizer.localize(resource.readAllBytes(), "docx", "pt-BR");
            String xml = zipText(localized);
            check(xml.contains("pt-BR") && !xml.contains("en-US"), "Langue interne du nouveau DOCX localisée");
        }
    }

    private static String zipText(byte[] archiveBytes) throws Exception {
        StringBuilder result = new StringBuilder();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (entry.getName().endsWith(".xml")) result.append(new String(archive.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }

    private static void testOfficeOcsEnvelope() {
        Map<String, Object> wrapped = Json.parseObject("{\"ocs\":{\"meta\":{\"status\":\"ok\"},\"data\":"
            + "{\"documentServerUrl\":\"https://office.example.com\",\"token\":\"jwt\","
            + "\"document\":{},\"editorConfig\":{}}}}");
        Map<String, Object> config = NextcloudClient.unwrapOcsData(wrapped);
        check("jwt".equals(config.get("token")), "Extraction enveloppe OCS du serveur bureautique");
        check(config.get("document") instanceof Map, "Document bureautique présent");

        Map<String, Object> direct = Json.parseObject("{\"document\":{},\"editorConfig\":{},\"token\":\"direct\"}");
        check("direct".equals(NextcloudClient.unwrapOcsData(direct).get("token")),
            "Configuration bureautique directe");
    }

    private static void testPublicShareForm() {
        String form = NextcloudClient.buildPublicShareForm("/Dossier & test/fichier.pdf", "s ecret&", "2026-08-31");
        check(form.contains("path=%2FDossier+%26+test%2Ffichier.pdf"), "Chemin partage encodé");
        check(form.contains("shareType=3"), "Partage par lien public");
        check(form.contains("permissions=1"), "Partage explicitement en lecture seule");
        check(form.contains("publicUpload=false"), "Dépôt public désactivé");
        check(form.contains("password=s+ecret%26"), "Mot de passe partage encodé");
        check(form.contains("expireDate=2026-08-31"), "Expiration partage encodée");
    }

    private static void testWebDavParsing() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-webdav-test-");
        Path file = root.resolve("config.properties");
        String properties = "storage.dir=" + root.resolve("data") + "\n"
            + "zimbra.public_url=https://mail.example.com\n"
            + "nextcloud.allow_http=false\n"
            + "nextcloud.block_private_networks=false\n"
            + "onlyoffice.public_url=https://office.example.com\n"
            + "onlyoffice.download_hosts=office.example.com\n"
            + "onlyoffice.jwt_secret=01234567890123456789012345678901\n"
            + "onlyoffice.jwt_header=Authorization\n"
			+ "security.ticket_secret=0123456789012345678901234567890123456789012345678901234567890123\n";
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        String previous = System.getProperty("nextcloud.zimlet.config");
        System.setProperty("nextcloud.zimlet.config", file.toString());
        try {
            AppConfig config = AppConfig.load();
            Profile profile = new Profile("https://cloud.example.com", "franck", "secret", 1L);
            NextcloudClient client = new NextcloudClient(config, profile);
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\" xmlns:nc=\"http://nextcloud.org/ns\">"
                + "<d:response><d:href>/remote.php/dav/files/franck/</d:href><d:propstat><d:prop>"
                + "<d:displayname>franck</d:displayname><d:resourcetype><d:collection/></d:resourcetype>"
                + "<d:quota-used-bytes>1073741824</d:quota-used-bytes><d:quota-available-bytes>3221225472</d:quota-available-bytes>"
                + "</d:prop></d:propstat></d:response>"
                + "<d:response><d:href>/remote.php/dav/files/franck/Dossier%20test/</d:href><d:propstat><d:prop>"
                + "<d:displayname>Dossier test</d:displayname><d:resourcetype><d:collection/></d:resourcetype>"
                + "<d:creationdate>2026-08-01T12:00:00Z</d:creationdate><d:getlastmodified>Fri, 07 Aug 2026 12:00:00 GMT</d:getlastmodified>"
                + "<oc:fileid>42</oc:fileid><oc:permissions>RGDNVCK</oc:permissions><oc:favorite>1</oc:favorite>"
                + "<oc:owner-id>alice</oc:owner-id><oc:owner-display-name>Alice</oc:owner-display-name>"
                + "<oc:checksums><oc:checksum>SHA256:abc</oc:checksum></oc:checksums><oc:tags><oc:tag>Projet</oc:tag></oc:tags>"
                + "<nc:has-preview>true</nc:has-preview><nc:lock>1</nc:lock><nc:lock-owner>alice</nc:lock-owner>"
                + "<nc:lock-owner-displayname>Alice</nc:lock-owner-displayname><nc:lock-time>1786123456</nc:lock-time>"
                + "</d:prop></d:propstat></d:response></d:multistatus>";
            java.util.List<Map<String, Object>> items = client.parseMultistatus(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "/"
            );
            check(items.size() == 2, "WebDAV multistatus");
            check("/".equals(items.get(0).get("path")), "Chemin WebDAV racine");
            check("/Dossier test".equals(items.get(1).get("path")), "Décodage chemin WebDAV");
            check(Boolean.TRUE.equals(items.get(1).get("isDirectory")), "Dossier WebDAV");
            check("2026-08-01T12:00:00Z".equals(items.get(1).get("created")), "Date de création WebDAV");
            check(Long.valueOf(1073741824L).equals(items.get(0).get("quotaUsed")), "Quota utilisé WebDAV");
            check(Boolean.TRUE.equals(items.get(1).get("canRead")) && Boolean.TRUE.equals(items.get(1).get("canShare")),
                "Permissions de lecture et partage WebDAV");
            check(Boolean.FALSE.equals(items.get(1).get("canDelete")) && Boolean.FALSE.equals(items.get(1).get("canCreateFile")),
                "Verrou distant appliqué aux actions de modification");
            check(Boolean.TRUE.equals(items.get(1).get("lockedByOther")) && Boolean.TRUE.equals(items.get(1).get("favorite")),
                "Verrou et favori WebDAV");
            check(((java.util.List<?>) items.get(1).get("tags")).contains("Projet")
                && ((java.util.List<?>) items.get(1).get("checksums")).contains("SHA256:abc"),
                "Métadonnées WebDAV enrichies");

            String dangerous = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&e;</d:href></d:response></d:multistatus>";
            expectFailure(() -> client.parseMultistatus(
                new ByteArrayInputStream(dangerous.getBytes(StandardCharsets.UTF_8)), "/"
            ), "XML externe WebDAV refusé");
        } finally {
            if (previous == null) System.clearProperty("nextcloud.zimlet.config");
            else System.setProperty("nextcloud.zimlet.config", previous);
            deleteTree(root);
        }
    }

    private static void testVersionDavWorkflow() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-versions-test-");
        Path file = root.resolve("config.properties");
        String properties = "storage.dir=" + root.resolve("data") + "\n"
            + "zimbra.public_url=https://mail.example.com\n"
            + "nextcloud.allow_http=true\n"
            + "nextcloud.block_private_networks=true\n"
            + "nextcloud.private_hosts=127.0.0.1\n"
            + "onlyoffice.public_url=https://office.example.com\n"
            + "onlyoffice.download_hosts=office.example.com\n"
			+ "onlyoffice.jwt_secret=01234567890123456789012345678901\n"
			+ "onlyoffice.jwt_header=Authorization\n"
			+ "security.ticket_secret=0123456789012345678901234567890123456789012345678901234567890123\n";
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        String previous = System.getProperty("nextcloud.zimlet.config");
        System.setProperty("nextcloud.zimlet.config", file.toString());
        try {
            String baseUrl = "http://127.0.0.1:18080/nextcloud";
            String versionPath = "/nextcloud/remote.php/dav/versions/franck/versions/104135/1786304895";
            FakeVersionTransport transport = new FakeVersionTransport(versionPath);
            NextcloudClient client = new NextcloudClient(AppConfig.load(),
                new Profile(baseUrl, "franck", "application-secret", 1L), transport);

            List<Map<String, Object>> versions = client.listVersions("104135");
            check(versions.size() == 1 && "1786304895".equals(versions.get(0).get("versionId")),
                "Versions : identifiant exact renvoyé par Nextcloud");

            HttpResponse<InputStream> downloaded = client.getVersion("104135", "1786304895");
            check("historical-version".equals(new String(downloaded.body().readAllBytes(), StandardCharsets.UTF_8)),
                "Versions : contenu téléchargé");
            check(versionPath.equals(transport.lastGet.uri().getRawPath()),
                "Versions : téléchargement depuis le href WebDAV exact");

            client.restoreVersion("104135", "1786304895");
            check("MOVE".equals(transport.lastMove.method()) && versionPath.equals(transport.lastMove.uri().getRawPath()),
                "Versions : restauration par MOVE de la version exacte");
            check((baseUrl + "/remote.php/dav/versions/franck/restore/target").equals(
                    transport.lastMove.headers().firstValue("Destination").orElse("")),
                "Versions : destination officielle restore/target");
            check("T".equals(transport.lastMove.headers().firstValue("Overwrite").orElse("")),
                "Versions : restauration explicite et idempotente");
        } finally {
            if (previous == null) System.clearProperty("nextcloud.zimlet.config");
            else System.setProperty("nextcloud.zimlet.config", previous);
            deleteTree(root);
        }
    }

    private static void testWebDavSearchBody() {
        String body = NextcloudClient.buildSearchBody(
            "franck", "/Dossier & devis", "budget <2026> & urgent"
        );
        check(body.contains("<d:href>/files/franck/Dossier%20%26%20devis</d:href>"),
            "Portée WebDAV SEARCH encodée");
        check(body.contains("%budget &lt;2026&gt; &amp; urgent%"),
            "Recherche WebDAV XML échappée");
        check(body.contains("<d:depth>infinity</d:depth>"), "Recherche WebDAV récursive");
        check(body.contains("<d:nresults>500</d:nresults>"), "Recherche WebDAV limitée");
    }

    private static void testTalkApis() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-talk-test-");
        Path file = root.resolve("config.properties");
        String properties = "storage.dir=" + root.resolve("data") + "\n"
            + "zimbra.public_url=https://mail.example.com\n"
            + "nextcloud.allow_http=false\n"
            + "nextcloud.block_private_networks=false\n"
            + "onlyoffice.public_url=https://office.example.com\n"
            + "onlyoffice.download_hosts=office.example.com\n"
            + "onlyoffice.jwt_secret=01234567890123456789012345678901\n"
            + "onlyoffice.jwt_header=Authorization\n"
            + "talk.request_timeout_seconds=9\n"
            + "security.ticket_secret=0123456789012345678901234567890123456789012345678901234567890123\n";
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        String previous = System.getProperty("nextcloud.zimlet.config");
        System.setProperty("nextcloud.zimlet.config", file.toString());
        try {
            FakeTalkTransport transport = new FakeTalkTransport();
            NextcloudTalkClient client = new NextcloudTalkClient(AppConfig.load(),
                new Profile("https://cloud.example.com", "franck", "app-secret", 1L), transport);
            Map<String, Object> status = client.status();
            check(Json.bool(status, "available", false) && Json.bool(status, "reactions", false)
                    && Json.bool(status, "deleteMessages", false),
                "Talk détecté par capacités OCS");
            check(transport.lastRequest != null && transport.lastRequest.timeout().orElseThrow().getSeconds() == 9L,
                "Délai Talk interactif distinct et borné");
            List<Map<String, Object>> conversations = client.conversations();
            check(conversations.size() == 1 && "abcd1234".equals(conversations.get(0).get("token")),
                "Conversations Talk v4");
            List<Map<String, Object>> messages = client.messages("abcd1234", 41L, 50, true);
            check(messages.size() == 1 && Json.longValue(messages.get(0), "id", 0L) == 42L,
                "Messages Talk normalisés");
            check(transport.lastChatGet.uri().getQuery().contains("lookIntoFuture=1")
                    && transport.lastChatGet.uri().getQuery().contains("timeout=1"),
                "Polling Talk court et borné");
            Map<String, Object> sent = client.sendMessage("abcd1234", "Bonjour Talk", 41L);
            check(Json.longValue(sent, "id", 0L) == 43L && "POST".equals(transport.lastMessagePost.method()),
                "Envoi et réponse Talk");
            Map<String, Object> created = client.createConversation(3, "Nouvelle équipe", "");
            check("newroom1".equals(created.get("token")) && "POST".equals(transport.lastConversationPost.method()),
                "Création d’une conversation Talk");
            client.deleteMessage("abcd1234", 43L);
            check("DELETE".equals(transport.lastMessageDelete.method()), "Suppression d’un message Talk");
            client.markRead("abcd1234", 43L);
            client.setReaction("abcd1234", 43L, "👍", false);
            client.shareFile("abcd1234", "/Documents/test.odt", 0L);
            Map<String, Object> gifs = client.gifs("bonjour", 8, 24);
            check(((List<?>) gifs.get("items")).size() == 1, "GIF via intégration Nextcloud Giphy");
            check(transport.lastGifSearch != null && transport.lastGifSearch.uri().getQuery().contains("cursor=24"),
                "Curseur de pagination GIF transmis à Nextcloud");
            try (InputStream gif = client.getGif("https://cloud.example.com/index.php/apps/integration_giphy/gif/1").body()) {
                check("gif-image".equals(new String(gif.readAllBytes(), StandardCharsets.UTF_8)),
                    "Miniature GIF suivie jusqu’au CDN Giphy autorisé");
            }
            check(transport.lastGifCdn != null
                    && transport.lastGifCdn.headers().firstValue("Authorization").isEmpty(),
                "Identifiants Nextcloud jamais transmis au CDN Giphy");
            check(transport.paths.stream().noneMatch(path -> path.contains("/call") || path.contains("signaling")),
                "Aucune API audio, vidéo ou signalisation");
            expectFailure(() -> client.getGif("https://evil.example.net/image.gif"),
                "Proxy GIF limité à l’origine Nextcloud");
        } finally {
            if (previous == null) System.clearProperty("nextcloud.zimlet.config");
            else System.setProperty("nextcloud.zimlet.config", previous);
            deleteTree(root);
        }
    }

    private static void testWebDavZipDownload() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-zip-test-");
        Path file = root.resolve("config.properties");
        String properties = "storage.dir=" + root.resolve("data") + "\n"
            + "zimbra.public_url=https://mail.example.com\n"
            + "nextcloud.allow_http=false\n"
            + "nextcloud.block_private_networks=false\n"
            + "office.provider=onlyoffice\n"
            + "office.public_url=https://office.example.com\n"
            + "office.download_hosts=office.example.com\n"
            + "office.security_mode=none\n"
            + "office.jwt_header=Authorization\n"
            + "security.ticket_secret=0123456789012345678901234567890123456789012345678901234567890123\n";
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        String previous = System.getProperty("nextcloud.zimlet.config");
        System.setProperty("nextcloud.zimlet.config", file.toString());
        try {
            HttpRequest[] captured = new HttpRequest[1];
            NextcloudClient client = new NextcloudClient(AppConfig.load(),
                new Profile("https://cloud.example.com", "franck", "app-secret", 1L), request -> {
                    captured[0] = request;
                    return response(200, request, "zip-content", Map.of("Content-Type", List.of("application/zip")));
                });
            try (InputStream input = client.getZip("/Photos", List.of("été 1.jpg", "été 2.jpg")).body()) {
                check("zip-content".equals(new String(input.readAllBytes(), StandardCharsets.UTF_8)),
                    "Archive ZIP WebDAV transmise");
            }
            String query = captured[0].uri().getRawQuery();
            check(query.contains("accept=zip") && query.contains("files=%5B%22"),
                "Archive ZIP via paramètres WebDAV Nextcloud documentés");
            check("application/zip".equals(captured[0].headers().firstValue("Accept").orElse("")),
                "Type ZIP demandé explicitement");
        } finally {
            if (previous == null) System.clearProperty("nextcloud.zimlet.config");
            else System.setProperty("nextcloud.zimlet.config", previous);
            deleteTree(root);
        }
    }

    private static void testBulkDestinations() {
        check("/Archives/rapport.pdf".equals(NextcloudClient.bulkDestination("/Documents/rapport.pdf", "/Archives")),
            "Destination groupée construite dans le même compte");
        check("/Archives/Dossier".equals(NextcloudClient.bulkDestination("/Documents/Dossier", "/Archives")),
            "Dossier déplaçable ou copiable");
        expectFailure(() -> NextcloudClient.bulkDestination("/Documents/rapport.pdf", "/Documents"),
            "Destination identique refusée");
        expectFailure(() -> NextcloudClient.bulkDestination("/Documents", "/Documents/Sous-dossier"),
            "Déplacement d’un dossier dans lui-même refusé");
    }

    private static void testRequestLimiter() throws Exception {
        RequestLimiter limiter = new RequestLimiter(2, 2);
        try (RequestLimiter.Lease ignored = limiter.enter("account")) {}
        try (RequestLimiter.Lease ignored = limiter.enter("account")) {}
        expectFailure(() -> limiter.enter("account"), "Limite de requêtes par compte appliquée");
    }

    private static void testTrashParsing() throws Exception {
        Path root = Files.createTempDirectory("nextcloud-trash-test-");
        Path file = root.resolve("config.properties");
        String properties = "storage.dir=" + root.resolve("data") + "\n"
            + "zimbra.public_url=https://mail.example.com\n"
            + "nextcloud.allow_http=false\n"
            + "nextcloud.block_private_networks=false\n"
            + "onlyoffice.public_url=https://office.example.com\n"
            + "onlyoffice.download_hosts=office.example.com\n"
            + "onlyoffice.jwt_secret=01234567890123456789012345678901\n"
            + "onlyoffice.jwt_header=Authorization\n"
            + "security.ticket_secret=0123456789012345678901234567890123456789012345678901234567890123\n";
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        String previous = System.getProperty("nextcloud.zimlet.config");
        System.setProperty("nextcloud.zimlet.config", file.toString());
        try {
            NextcloudClient client = new NextcloudClient(AppConfig.load(),
                new Profile("https://cloud.example.com", "franck", "secret", 1L));
            String xml = "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:nc=\"http://nextcloud.org/ns\">"
                + "<d:response><d:href>/remote.php/dav/trashbin/franck/trash/</d:href><d:propstat><d:prop>"
                + "<d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>"
                + "<d:response><d:href>/remote.php/dav/trashbin/franck/trash/rapport.pdf.d1786123456</d:href><d:propstat><d:prop>"
                + "<d:getcontentlength>1234</d:getcontentlength><nc:trashbin-filename>rapport.pdf</nc:trashbin-filename>"
                + "<nc:trashbin-original-location>Documents/rapport.pdf</nc:trashbin-original-location>"
                + "<nc:trashbin-deletion-time>1786123456</nc:trashbin-deletion-time>"
                + "</d:prop></d:propstat></d:response></d:multistatus>";
            java.util.List<Map<String, Object>> items = client.parseTrashMultistatus(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))
            );
            check(items.size() == 2, "WebDAV corbeille multistatus");
            check("rapport.pdf".equals(items.get(1).get("name")), "Nom original corbeille");
            check("Documents/rapport.pdf".equals(items.get(1).get("trashOriginalLocation")), "Emplacement original corbeille");
            check(String.valueOf(items.get(1).get("trashDeletionTime")).startsWith("2026-"), "Date de suppression corbeille");
        } finally {
            if (previous == null) System.clearProperty("nextcloud.zimlet.config");
            else System.setProperty("nextcloud.zimlet.config", previous);
            deleteTree(root);
        }
    }

    private static void testFailSafeConstruction() {
        String previous = System.getProperty("nextcloud.zimlet.config");
        System.setProperty("nextcloud.zimlet.config", "/fichier/inexistant/volontaire.properties");
        try {
            new NextcloudConnectorHandler();
        } finally {
            if (previous == null) System.clearProperty("nextcloud.zimlet.config");
            else System.setProperty("nextcloud.zimlet.config", previous);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        }
    }

    private static void expectFailure(ThrowingRunnable runnable, String label) {
        try {
            runnable.run();
            throw new AssertionError(label);
        } catch (AssertionError e) {
            throw e;
        } catch (Exception expected) {}
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static final class FakeVersionTransport implements NextcloudClient.Transport {
        private final String versionPath;
        HttpRequest lastGet;
        HttpRequest lastMove;

        FakeVersionTransport(String versionPath) {
            this.versionPath = versionPath;
        }

        @Override
        public HttpResponse<InputStream> send(HttpRequest request) {
            if ("PROPFIND".equals(request.method())) {
                String xml = "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:nc=\"http://nextcloud.org/ns\">"
                    + "<d:response><d:href>" + request.uri().getRawPath() + "</d:href><d:propstat><d:prop>"
                    + "<d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>"
                    + "<d:response><d:href>" + versionPath + "</d:href><d:propstat><d:prop>"
                    + "<d:getcontentlength>18</d:getcontentlength>"
                    + "<d:getlastmodified>Sun, 09 Aug 2026 10:28:15 GMT</d:getlastmodified>"
                    + "<d:getetag>\"version-etag\"</d:getetag><nc:version-author>franck</nc:version-author>"
                    + "</d:prop></d:propstat></d:response></d:multistatus>";
                return response(207, request, xml);
            }
            if ("GET".equals(request.method())) {
                lastGet = request;
                return versionPath.equals(request.uri().getRawPath())
                    ? response(200, request, "historical-version") : response(404, request, "missing");
            }
            if ("MOVE".equals(request.method())) {
                lastMove = request;
                String destination = request.headers().firstValue("Destination").orElse("");
                return versionPath.equals(request.uri().getRawPath()) && destination.endsWith("/restore/target")
                    ? response(201, request, "") : response(403, request, "forbidden");
            }
            throw new AssertionError("Requête WebDAV simulée inattendue : " + request.method());
        }
    }

    private static final class FakeTalkTransport implements NextcloudTalkClient.Transport {
        final List<String> paths = new ArrayList<>();
        HttpRequest lastChatGet;
        HttpRequest lastMessagePost;
        HttpRequest lastConversationPost;
        HttpRequest lastMessageDelete;
        HttpRequest lastGifSearch;
        HttpRequest lastGifCdn;
        HttpRequest lastRequest;

        @Override
        public HttpResponse<InputStream> send(HttpRequest request) {
            lastRequest = request;
            String path = request.uri().getRawPath();
            paths.add(path);
            if (path.endsWith("/cloud/capabilities")) {
                return response(200, request, "{\"ocs\":{\"meta\":{\"statuscode\":100},\"data\":{\"capabilities\":{\"spreed\":{\"features\":[\"chat-replies\",\"reactions\",\"delete-messages\",\"chat-read-marker\"],\"config\":{\"chat\":{\"max-length\":32000}}}}}}}");
            }
            if (path.endsWith("/api/v4/room") && "GET".equals(request.method())) {
                return response(200, request, "{\"ocs\":{\"meta\":{\"statuscode\":100},\"data\":[{\"token\":\"abcd1234\",\"displayName\":\"Équipe\",\"unreadMessages\":2}]}}");
            }
            if (path.endsWith("/api/v4/room") && "POST".equals(request.method())) {
                lastConversationPost = request;
                return response(201, request, "{\"ocs\":{\"meta\":{\"statuscode\":100},\"data\":{\"token\":\"newroom1\",\"displayName\":\"Nouvelle équipe\",\"type\":3}}}");
            }
            if (path.endsWith("/api/v1/chat/abcd1234") && "GET".equals(request.method())) {
                lastChatGet = request;
                return response(200, request, "{\"ocs\":{\"meta\":{\"statuscode\":100},\"data\":[{\"id\":42,\"token\":\"abcd1234\",\"actorId\":\"alice\",\"actorDisplayName\":\"Alice\",\"message\":\"Bonjour\",\"messageType\":\"comment\"}]}}");
            }
            if (path.endsWith("/api/v1/chat/abcd1234") && "POST".equals(request.method())) {
                lastMessagePost = request;
                return response(201, request, "{\"ocs\":{\"meta\":{\"statuscode\":100},\"data\":{\"id\":43,\"token\":\"abcd1234\",\"actorId\":\"franck\",\"message\":\"Bonjour Talk\",\"messageType\":\"comment\"}}}");
            }
            if (path.endsWith("/api/v1/chat/abcd1234/43") && "DELETE".equals(request.method())) {
                lastMessageDelete = request;
                return response(200, request, "{\"ocs\":{\"meta\":{\"statuscode\":100},\"data\":{}}}");
            }
            if (path.endsWith("/api/v1/chat/abcd1234/read")) {
                return response(200, request, "{\"ocs\":{\"meta\":{\"statuscode\":100},\"data\":[]}}");
            }
            if (path.endsWith("/api/v1/reaction/abcd1234/43")) {
                return response(201, request, "{\"ocs\":{\"meta\":{\"statuscode\":100},\"data\":[]}}");
            }
            if (path.endsWith("/apps/files_sharing/api/v1/shares")) {
                return response(200, request, "{\"ocs\":{\"meta\":{\"statuscode\":100},\"data\":{\"id\":12}}}");
            }
            if (path.endsWith("/apps/integration_giphy/api/v1/gifs/search")) {
                lastGifSearch = request;
                return response(200, request, "{\"ocs\":{\"meta\":{\"statuscode\":100},\"data\":{\"entries\":[{\"title\":\"Hello\",\"thumbnailUrl\":\"https://cloud.example.com/index.php/apps/integration_giphy/gif/1\",\"resourceUrl\":\"https://giphy.com/gifs/hello\"}],\"cursor\":1}}}");
            }
            if (path.endsWith("/apps/integration_giphy/gif/1")) {
                return response(302, request, "", Map.of("Location", List.of("https://media2.giphy.com/media/hello/200.gif")));
            }
            if ("media2.giphy.com".equals(request.uri().getHost()) && path.endsWith("/media/hello/200.gif")) {
                lastGifCdn = request;
                return response(200, request, "gif-image", Map.of("Content-Type", List.of("image/gif")));
            }
            throw new AssertionError("Requête Talk simulée inattendue : " + request.method() + " " + path);
        }
    }

    private static HttpResponse<InputStream> response(int status, HttpRequest request, String body) {
        return response(status, request, body, Map.of());
    }

    private static HttpResponse<InputStream> response(int status, HttpRequest request, String body,
                                                       Map<String, List<String>> responseHeaders) {
        return new HttpResponse<InputStream>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return request; }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(responseHeaders, (name, value) -> true); }
            @Override public InputStream body() {
                return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
            }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return request.uri(); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static final class FakeProvisioningTransport
        implements NextcloudProvisioningService.ProvisioningTransport {
        final List<HttpRequest> requests = new ArrayList<>();
        final ArrayDeque<String> responses = new ArrayDeque<>();

        FakeProvisioningTransport(String... responses) {
            for (String response : responses) this.responses.add(response);
        }

        @Override
        public NextcloudProvisioningService.TransportResponse send(HttpRequest request) {
            requests.add(request);
            if (responses.isEmpty()) throw new AssertionError("Réponse OCS simulée manquante");
            return NextcloudProvisioningService.TransportResponse.json(200, responses.removeFirst());
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
