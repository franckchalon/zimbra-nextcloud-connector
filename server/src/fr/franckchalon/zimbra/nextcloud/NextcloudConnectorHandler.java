package fr.franckchalon.zimbra.nextcloud;

import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AuthToken;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.extension.ExtensionHttpHandler;
import com.zimbra.cs.servlet.util.AuthUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class NextcloudConnectorHandler extends ExtensionHttpHandler {
    private static final Logger LOGGER = Logger.getLogger(NextcloudConnectorHandler.class.getName());
    private static final String EXTENSION_PATH = "/nextcloud-connector";
    private static final String URI_PREFIX = "/service/extension" + EXTENSION_PATH;
    private static final long MAX_JSON_BYTES = 2L * 1024L * 1024L;

    private volatile AppConfig config;
    private CredentialStore store;
    private TicketService tickets;
    private OnlyOfficeService onlyoffice;
    private NextcloudProvisioningService provisioning;
    private NextcloudLoginFlow loginFlow;
    private DocumentTemplateService templates;
    private RequestLimiter limiter;

    NextcloudConnectorHandler() {}

    @Override
    public String getPath() {
        return EXTENSION_PATH;
    }

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        secureHeaders(resp);
        ServerI18n.use(requestLanguage(req));
        String route = route(req);
        RequestLimiter.Lease lease = null;
        try {
            if ("/public/ping".equals(route)) {
                LinkedHashMap<String, Object> ping = new LinkedHashMap<>();
                ping.put("status", "ok");
                ping.put("version", Version.VALUE);
                writeJson(resp, 200, ping);
                return;
            }
            if ("/public/content".equals(route)) {
                ensureInitialized();
                handlePublicContent(req, resp);
                return;
            }

            AccountContext account = authenticate(req, resp);
            if ("/api/health".equals(route)) {
                LinkedHashMap<String, Object> health = new LinkedHashMap<>();
                health.put("version", Version.VALUE);
                health.put("time", Instant.now().toString());
                try {
                    ensureInitialized();
                    health.put("status", "ok");
                    health.put("configured", true);
                    health.put("officeProvider", config.officeProvider);
                    health.put("officeSecurityMode", config.officeSecurityMode);
                    health.put("nextcloudAccountMode", config.nextcloudAccountMode);
                } catch (HttpError e) {
                    health.put("status", "degraded");
                    health.put("configured", false);
                    health.put("message", ServerI18n.localize(e.getMessage()));
                }
                writeJson(resp, 200, health);
                return;
            }

            ensureInitialized();
            lease = limiter.enter(account.id);
            if ("/api/profile".equals(route)) {
                writeJson(resp, 200, publicProfile(account));
            } else if ("/api/talk/overview".equals(route)) {
                writeJson(resp, 200, talkOverview(account));
            } else if ("/api/talk/messages".equals(route)) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", talkClientFor(account.id, req).messages(req.getParameter("token"),
                    queryLong(req, "lastKnownMessageId", 0L, 0L, Long.MAX_VALUE),
                    queryInt(req, "limit", 100, 1, 200), "true".equalsIgnoreCase(req.getParameter("future"))));
                writeJson(resp, 200, result);
            } else if ("/api/talk/gifs".equals(route)) {
                writeJson(resp, 200, talkClientFor(account.id, req).gifs(req.getParameter("q"),
                    queryInt(req, "limit", 18, 1, 24), queryInt(req, "cursor", 0, 0, 10000)));
            } else if ("/api/talk/gif".equals(route)) {
                streamTalkGif(talkClientFor(account.id, req), req.getParameter("url"), resp);
            } else if ("/api/capabilities".equals(route)) {
                writeJson(resp, 200, clientFor(account.id, req).capabilities());
            } else if ("/api/templates".equals(route)) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", templates.list());
                writeJson(resp, 200, result);
            } else if ("/api/mail-limits".equals(route)) {
                writeJson(resp, 200, mailLimits(account));
            } else if ("/api/quota".equals(route)) {
                writeJson(resp, 200, clientFor(account.id, req).quota());
            } else if ("/api/stat".equals(route)) {
                writeJson(resp, 200, clientFor(account.id, req).stat(req.getParameter("path")));
            } else if ("/api/list".equals(route)) {
                Profile profile = requiredProfile(account.id, profileIdFromRequest(req));
                String path = PathUtil.normalize(req.getParameter("path"));
                List<Map<String, Object>> items = new NextcloudClient(config, profile).list(path);
                int offset = queryInt(req, "offset", 0, 0, config.maxDirectoryResponseItems);
                int limit = queryInt(req, "limit", config.maxDirectoryResponseItems, 1, config.maxDirectoryResponseItems);
                int total = items.size();
                int end = Math.min(total, offset + limit);
                List<Map<String, Object>> page = offset >= total ? new ArrayList<>() : new ArrayList<>(items.subList(offset, end));
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("path", path);
                result.put("items", page);
                result.put("offset", offset);
                result.put("limit", limit);
                result.put("total", total);
                result.put("hasMore", end < total);
                writeJson(resp, 200, result);
            } else if ("/api/search".equals(route)) {
                String query = req.getParameter("q");
                List<Map<String, Object>> items = clientFor(account.id, req).search(query, "/");
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("scope", "account");
                result.put("items", items);
                writeJson(resp, 200, result);
            } else if ("/api/search/advanced".equals(route)) {
                String query = req.getParameter("q");
                String scope = req.getParameter("scope");
                String category = req.getParameter("category");
                String modifiedAfter = req.getParameter("modifiedAfter");
                long minimumSize = queryLong(req, "minimumSize", -1L, -1L, config.maxUploadBytes);
                long maximumSize = queryLong(req, "maximumSize", -1L, -1L, Long.MAX_VALUE);
                int limit = queryInt(req, "limit", 500, 1, 1000);
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", clientFor(account.id, req).advancedSearch(query, scope, category,
                    modifiedAfter, minimumSize, maximumSize, limit));
                writeJson(resp, 200, result);
            } else if ("/api/favorites".equals(route)) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", clientFor(account.id, req).favorites());
                writeJson(resp, 200, result);
            } else if ("/api/recent".equals(route)) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", clientFor(account.id, req).recent(queryInt(req, "days", 30, 1, 365)));
                writeJson(resp, 200, result);
            } else if ("/api/shares".equals(route)) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", clientFor(account.id, req).listShares(req.getParameter("path"),
                    "true".equalsIgnoreCase(req.getParameter("sharedWithMe"))));
                writeJson(resp, 200, result);
            } else if ("/api/sharees".equals(route)) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", clientFor(account.id, req).searchSharees(req.getParameter("q"), req.getParameter("itemType")));
                writeJson(resp, 200, result);
            } else if ("/api/versions".equals(route)) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", clientFor(account.id, req).listVersions(req.getParameter("fileId")));
                writeJson(resp, 200, result);
            } else if ("/api/version/file".equals(route)) {
                streamVersion(clientFor(account.id, req), req.getParameter("fileId"), req.getParameter("versionId"),
                    req.getParameter("name"), resp);
            } else if ("/api/comments".equals(route)) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", clientFor(account.id, req).listComments(req.getParameter("fileId")));
                writeJson(resp, 200, result);
            } else if ("/api/activity".equals(route)) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", clientFor(account.id, req).activity(req.getParameter("fileId")));
                writeJson(resp, 200, result);
            } else if ("/api/archive".equals(route)) {
                streamArchive(clientFor(account.id, req), req, resp);
            } else if ("/api/diagnostics".equals(route)) {
                writeJson(resp, 200, diagnostics(account, req));
            } else if ("/api/trash".equals(route)) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("items", clientFor(account.id, req).listTrash());
                writeJson(resp, 200, result);
            } else if ("/api/thumbnail".equals(route)) {
                streamThumbnail(clientFor(account.id, req), req.getParameter("fileId"), resp);
            } else if ("/api/file".equals(route)) {
                handleAuthenticatedFile(req, resp, account);
            } else if ("/editor".equals(route)) {
                Profile profile = requiredProfile(account.id, profileIdFromRequest(req));
                String html = onlyoffice.editorHtml(account, profile, req.getParameter("path"), requestLanguage(req));
                resp.setStatus(200);
                resp.setCharacterEncoding("UTF-8");
                resp.setContentType("text/html; charset=UTF-8");
                resp.setHeader("Cache-Control", "no-store");
                resp.setHeader("X-Frame-Options", "SAMEORIGIN");
                resp.getWriter().write(html);
            } else {
                throw new HttpError(404, "Point d’accès inconnu");
            }
        } catch (HttpError e) {
            writeError(resp, e.status, e.getMessage());
        } catch (GeneralSecurityException e) {
            writeError(resp, 403, "Jeton de sécurité invalide");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeError(resp, 503, "Opération interrompue");
        } catch (IllegalArgumentException e) {
            writeError(resp, 400, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Erreur Nextcloud Connector sur " + route, e);
            writeError(resp, 500, "Erreur interne de la connexion Nextcloud");
        } catch (LinkageError e) {
            LOGGER.log(Level.SEVERE, "Dépendance incompatible dans Nextcloud Connector", e);
            writeError(resp, 503, "Le connecteur Nextcloud est indisponible, mais Zimbra reste opérationnel");
        } finally {
            if (lease != null) lease.close();
            ServerI18n.clear();
        }
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        secureHeaders(resp);
        ServerI18n.use(requestLanguage(req));
        String route = route(req);
        RequestLimiter.Lease lease = null;

        if ("/public/callback".equals(route)) {
            try {
                ensureInitialized();
                handlePublicCallback(req, resp);
            } catch (HttpError e) {
                writeError(resp, e.status, e.getMessage());
            } finally {
                ServerI18n.clear();
            }
            return;
        }

        try {
            requireZimletRequest(req);
            AccountContext account = authenticate(req, resp);
            ensureInitialized();
            lease = limiter.enter(account.id);

            if ("/api/profile".equals(route)) {
                saveProfile(req, resp, account);
            } else if ("/api/talk/settings".equals(route)) {
                updateTalkSettings(req, resp, account);
            } else if ("/api/talk/message".equals(route)) {
                Map<String, Object> body = readJson(req);
                Map<String, Object> message = talkClientFor(account.id, req).sendMessage(
                    Json.string(body, "token", ""), Json.string(body, "message", ""),
                    Json.longValue(body, "replyTo", 0L));
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("message", message);
                writeJson(resp, 200, result);
            } else if ("/api/talk/conversation".equals(route)) {
                Map<String, Object> body = readJson(req);
                Map<String, Object> conversation = talkClientFor(account.id, req).createConversation(
                    (int) Json.longValue(body, "roomType", 3L), Json.string(body, "roomName", ""),
                    Json.string(body, "invite", ""));
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("conversation", conversation);
                writeJson(resp, 200, result);
            } else if ("/api/talk/delete-message".equals(route)) {
                Map<String, Object> body = readJson(req);
                talkClientFor(account.id, req).deleteMessage(Json.string(body, "token", ""),
                    Json.longValue(body, "messageId", 0L));
                writeOk(resp);
            } else if ("/api/talk/read".equals(route)) {
                Map<String, Object> body = readJson(req);
                talkClientFor(account.id, req).markRead(Json.string(body, "token", ""),
                    Json.longValue(body, "lastReadMessage", 0L));
                writeOk(resp);
            } else if ("/api/talk/reaction".equals(route)) {
                Map<String, Object> body = readJson(req);
                talkClientFor(account.id, req).setReaction(Json.string(body, "token", ""),
                    Json.longValue(body, "messageId", 0L), Json.string(body, "reaction", ""),
                    Json.bool(body, "remove", false));
                writeOk(resp);
            } else if ("/api/talk/share-file".equals(route)) {
                Map<String, Object> body = readJson(req);
                Map<String, Object> share = talkClientFor(account.id, req).shareFile(
                    Json.string(body, "token", ""), Json.string(body, "path", ""),
                    Json.longValue(body, "replyTo", 0L));
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("share", share);
                writeJson(resp, 200, result);
            } else if ("/api/profile/select".equals(route)) {
                selectProfile(req, resp, account);
            } else if ("/api/profile/delete".equals(route)) {
                deleteProfile(req, resp, account);
            } else if ("/api/login-flow/start".equals(route)) {
                startLoginFlow(req, resp);
            } else if ("/api/login-flow/poll".equals(route)) {
                pollLoginFlow(req, resp, account);
            } else if ("/api/activate".equals(route)) {
                NextcloudProvisioningService.ActivationResult activation = provisioning.activate(account);
                writeJson(resp, 200, activation.toMap(publicProfile(account, Optional.of(activation.profile))));
            } else if ("/api/share".equals(route)) {
                createShare(req, resp, account);
            } else if ("/api/share/create".equals(route)) {
                createManagedShare(req, resp, account);
            } else if ("/api/share/update".equals(route)) {
                updateManagedShare(req, resp, account);
            } else if ("/api/share/delete".equals(route)) {
                deleteManagedShare(req, resp, account);
            } else if ("/api/favorite".equals(route)) {
                Map<String, Object> body = readJson(req);
                clientFor(account.id, req).setFavorite(Json.string(body, "path", ""), Json.bool(body, "favorite", true));
                writeOk(resp);
            } else if ("/api/upload".equals(route)) {
                upload(req, resp, account);
            } else if ("/api/upload/start".equals(route)) {
                startChunkedUpload(req, resp, account);
            } else if ("/api/upload/empty".equals(route)) {
                uploadEmpty(req, resp, account);
            } else if ("/api/upload/chunk".equals(route)) {
                uploadChunk(req, resp, account);
            } else if ("/api/upload/finish".equals(route)) {
                finishChunkedUpload(req, resp, account);
            } else if ("/api/upload/cancel".equals(route)) {
                cancelChunkedUpload(req, resp, account);
            } else if ("/api/create".equals(route)) {
                create(req, resp, account);
            } else if ("/api/move".equals(route)) {
                Map<String, Object> body = readJson(req);
                NextcloudClient client = clientFor(account.id, req);
                client.move(Json.string(body, "from", ""), Json.string(body, "to", ""));
                writeOk(resp);
            } else if ("/api/delete".equals(route)) {
                Map<String, Object> body = readJson(req);
                clientFor(account.id, req).delete(Json.string(body, "path", ""));
                writeOk(resp);
            } else if ("/api/batch".equals(route)) {
                batch(req, resp, account);
            } else if ("/api/trash/restore".equals(route)) {
                Map<String, Object> body = readJson(req);
                clientFor(account.id, req).restoreTrash(Json.string(body, "trashId", ""));
                writeOk(resp);
            } else if ("/api/trash/delete".equals(route)) {
                Map<String, Object> body = readJson(req);
                clientFor(account.id, req).deleteTrash(Json.string(body, "trashId", ""));
                writeOk(resp);
            } else if ("/api/trash/empty".equals(route)) {
                clientFor(account.id, req).emptyTrash();
                writeOk(resp);
            } else if ("/api/version/restore".equals(route)) {
                Map<String, Object> body = readJson(req);
                clientFor(account.id, req).restoreVersion(Json.string(body, "fileId", ""), Json.string(body, "versionId", ""));
                writeOk(resp);
            } else if ("/api/comment/add".equals(route)) {
                Map<String, Object> body = readJson(req);
                clientFor(account.id, req).addComment(Json.string(body, "fileId", ""), Json.string(body, "message", ""));
                writeOk(resp);
            } else if ("/api/comment/delete".equals(route)) {
                Map<String, Object> body = readJson(req);
                clientFor(account.id, req).deleteComment(Json.string(body, "fileId", ""), Json.string(body, "commentId", ""));
                writeOk(resp);
            } else if ("/api/disconnect".equals(route)) {
                deleteProfile(req, resp, account);
            } else {
                throw new HttpError(404, "Point d’accès inconnu");
            }
        } catch (HttpError e) {
            writeError(resp, e.status, e.getMessage());
        } catch (GeneralSecurityException e) {
            writeError(resp, 403, "Erreur de chiffrement ou jeton invalide");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeError(resp, 503, "Opération interrompue");
        } catch (IllegalArgumentException e) {
            writeError(resp, 400, e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Erreur Nextcloud Connector sur " + route, e);
            writeError(resp, 500, "Erreur interne de la connexion Nextcloud");
        } catch (LinkageError e) {
            LOGGER.log(Level.SEVERE, "Dépendance incompatible dans Nextcloud Connector", e);
            writeError(resp, 503, "Le connecteur Nextcloud est indisponible, mais Zimbra reste opérationnel");
        } finally {
            if (lease != null) lease.close();
            ServerI18n.clear();
        }
    }

    private void saveProfile(HttpServletRequest req, HttpServletResponse resp, AccountContext account)
        throws Exception {
        Map<String, Object> body = readJson(req);
        ProfileSet profiles = store.loadProfiles(account.id).orElse(ProfileSet.empty());
        String requestedId = Json.string(body, "profileId", "").trim();
        Optional<Profile> existing = requestedId.isBlank() ? Optional.empty() : profiles.byId(requestedId);
        if (config.managedAccountsEnabled() && profiles.profiles.isEmpty()) {
            throw new HttpError(403, "Activez d’abord le compte Nextcloud géré préparé par votre organisation");
        }
        boolean managed = existing.isPresent() && existing.get().managed;
        String url = managed ? existing.get().nextcloudUrl
            : config.validateNextcloudUrl(Json.string(body, "nextcloudUrl", ""));
        String username = managed ? existing.get().username : Json.string(body, "username", "").trim();
        String password = managed ? existing.get().appPassword : Json.string(body, "appPassword", "");
        String label = managed ? existing.get().label : Json.string(body, "label", "").trim();
        if (username.isBlank() || username.length() > 320) throw new HttpError(400, "Identifiant Nextcloud invalide");
        if (password.length() > 4096) throw new HttpError(400, "Mot de passe d’application trop long");
        if (label.length() > 80) throw new HttpError(400, "Le nom du compte Cloud est trop long");

        if (password.isBlank() && existing.isPresent()) password = existing.get().appPassword;
        if (password.isBlank()) throw new HttpError(400, "Le mot de passe d’application Nextcloud est obligatoire");

        OfficeProfile officeProfile;
        String officeMode = Json.string(body, "officeMode", OfficeProfile.GLOBAL).trim().toLowerCase(java.util.Locale.ROOT);
        if (OfficeProfile.CUSTOM.equals(officeMode)) {
            String jwtSecret = Json.string(body, "officeJwtSecret", "");
            if (jwtSecret.isBlank() && existing.isPresent()
                    && OfficeProfile.CUSTOM.equals(existing.get().office.mode)) {
                jwtSecret = existing.get().office.jwtSecret;
            }
            officeProfile = OfficeProfile.custom(
                config,
                Json.string(body, "officeProvider", "onlyoffice"),
                Json.string(body, "officeUrl", ""),
                Json.string(body, "officeSecurityMode", "jwt"),
                Json.string(body, "officeJwtHeader", "Authorization"),
                jwtSecret
            );
        } else if (OfficeProfile.GLOBAL.equals(officeMode)) {
            officeProfile = OfficeProfile.global();
        } else {
            throw new HttpError(400, "Le mode du serveur bureautique est invalide");
        }

        Profile generated = new Profile(url, username, password, Instant.now().getEpochSecond());
        String profileId = Profile.validId(requestedId) ? requestedId : generated.id;
        Profile candidate = new Profile(profileId, label, url, username, password,
            Instant.now().getEpochSecond(), managed, officeProfile);
        new NextcloudClient(config, candidate).list("/");
        try {
            store.saveProfiles(account.id, profiles.upsert(candidate, true));
        } catch (IllegalArgumentException e) {
            throw new HttpError(409, e.getMessage(), e);
        }
        writeJson(resp, 200, publicProfile(account));
    }

    private void selectProfile(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        String profileId = Json.string(body, "profileId", "").trim();
        try {
            store.select(account.id, profileId);
        } catch (IllegalArgumentException e) {
            throw new HttpError(404, e.getMessage(), e);
        }
        writeJson(resp, 200, publicProfile(account));
    }

    private void updateTalkSettings(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        boolean enabled = Json.bool(body, "enabled", false);
        ProfileSet profiles = store.loadProfiles(account.id).orElse(ProfileSet.empty());
        String profileId = Json.string(body, "profileId", profileIdFromRequest(req)).trim();
        Profile profile = profiles.byId(profileId)
            .orElseThrow(() -> new HttpError(409, "Configurez d’abord un compte Nextcloud"));
        if (enabled) {
            Map<String, Object> accountOverview = talkAccountOverview(profile);
            if (!Json.bool(accountOverview, "available", false)) {
                throw new HttpError(409, "Nextcloud Talk n’est pas disponible sur ce compte");
            }
        }
        ProfileSet updated;
        try {
            updated = profiles.withTalkEnabled(profile.id, enabled);
        } catch (IllegalArgumentException e) {
            throw new HttpError(404, e.getMessage(), e);
        }
        store.saveProfiles(account.id, updated);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("profile", publicProfile(account, updated));
        if (updated.anyTalkEnabled()) result.put("overview", talkOverview(account));
        writeJson(resp, 200, result);
    }

    private void deleteProfile(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        String profileId = Json.string(body, "profileId", profileIdFromRequest(req)).trim();
        Profile profile = requiredProfile(account.id, profileId);
        if (profile.managed) {
            throw new HttpError(403, "La connexion du compte Nextcloud géré ne peut pas être supprimée par l’utilisateur");
        }
        boolean revoke = Json.bool(body, "revokeAppPassword", true);
        boolean revoked = false;
        if (revoke) {
            try {
                new NextcloudClient(config, profile).revokeCurrentAppPassword();
                revoked = true;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Le mot de passe d’application Nextcloud n’a pas pu être révoqué", e);
            }
        }
        store.delete(account.id, profile.id);
        Map<String, Object> result = publicProfile(account);
        result.put("remoteRevocationRequested", revoke);
        result.put("remoteRevoked", revoked);
        writeJson(resp, 200, result);
    }

    private void startLoginFlow(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Map<String, Object> body = readJson(req);
        writeJson(resp, 200, loginFlow.start(Json.string(body, "nextcloudUrl", "")));
    }

    private void pollLoginFlow(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        Map<String, Object> flow = loginFlow.poll(
            Json.string(body, "server", ""), Json.string(body, "pollEndpoint", ""),
            Json.string(body, "pollToken", "")
        );
        if (Json.bool(flow, "pending", false)) {
            writeJson(resp, 202, flow);
            return;
        }
        ProfileSet profiles = store.loadProfiles(account.id).orElse(ProfileSet.empty());
        if (config.managedAccountsEnabled() && profiles.profiles.isEmpty()) {
            throw new HttpError(403, "Activez d’abord le compte Nextcloud géré préparé par votre organisation");
        }
        String label = Json.string(body, "label", "").trim();
        if (label.length() > 80) throw new HttpError(400, "Le nom du compte Cloud est trop long");
        Profile generated = new Profile(Json.string(flow, "server", ""), Json.string(flow, "username", ""),
            Json.string(flow, "appPassword", ""), Instant.now().getEpochSecond());
        Profile candidate = new Profile(generated.id, label, generated.nextcloudUrl, generated.username,
            generated.appPassword, generated.updatedAt, false, OfficeProfile.global());
        new NextcloudClient(config, candidate).list("/");
        try { store.saveProfiles(account.id, profiles.upsert(candidate, true)); }
        catch (IllegalArgumentException e) { throw new HttpError(409, e.getMessage(), e); }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("pending", false);
        result.put("profile", publicProfile(account));
        writeJson(resp, 200, result);
    }

    private void createManagedShare(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        validateExpiration(Json.string(body, "expireDate", ""));
        int shareType = (int) Json.longValue(body, "shareType", 3L);
        int permissions = (int) Json.longValue(body, "permissions", 1L);
        Map<String, Object> result = clientFor(account.id, req).createShare(
            Json.string(body, "path", ""), shareType, Json.string(body, "shareWith", ""), permissions,
            Json.string(body, "password", ""), Json.string(body, "expireDate", ""),
            Json.string(body, "note", ""), Json.string(body, "label", "")
        );
        writeJson(resp, 200, result);
    }

    private void updateManagedShare(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        validateExpiration(Json.string(body, "expireDate", ""));
        writeJson(resp, 200, clientFor(account.id, req).updateShare(
            Json.string(body, "id", ""), (int) Json.longValue(body, "permissions", -1L),
            body.containsKey("password") ? Json.string(body, "password", "") : null,
            body.containsKey("expireDate") ? Json.string(body, "expireDate", "") : null,
            body.containsKey("note") ? Json.string(body, "note", "") : null
        ));
    }

    private void deleteManagedShare(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        clientFor(account.id, req).deleteShare(Json.string(body, "id", ""));
        writeOk(resp);
    }

    private void startChunkedUpload(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        Map<String, Object> result = clientFor(account.id, req).startChunkedUpload(
            Json.string(body, "path", ""), Json.longValue(body, "totalBytes", -1L),
            Json.string(body, "collisionPolicy", "ask"), Json.bool(body, "createParents", false)
        );
        writeJson(resp, 200, result);
    }

    private void uploadEmpty(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        writeJson(resp, 200, clientFor(account.id, req).putEmptyUpload(
            Json.string(body, "path", ""), Json.string(body, "collisionPolicy", "ask"),
            Json.bool(body, "createParents", false)
        ));
    }

    private void uploadChunk(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        int index = queryInt(req, "index", -1, 1, 10000);
        long totalBytes = queryLong(req, "totalBytes", -1L, 0L, config.maxUploadBytes);
        byte[] content = readBinary(req, config.uploadChunkBytes);
        clientFor(account.id, req).putUploadChunk(req.getParameter("uploadId"), index, totalBytes, content);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("index", index);
        result.put("bytes", content.length);
        writeJson(resp, 200, result);
    }

    private void finishChunkedUpload(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        clientFor(account.id, req).finishChunkedUpload(
            Json.string(body, "uploadId", ""), Json.string(body, "targetPath", ""),
            Json.longValue(body, "totalBytes", -1L), Json.bool(body, "replace", false)
        );
        writeOk(resp);
    }

    private void cancelChunkedUpload(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        clientFor(account.id, req).cancelChunkedUpload(Json.string(body, "uploadId", ""));
        writeOk(resp);
    }

    private void upload(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        long announced = req.getContentLengthLong();
        if (announced > config.maxUploadBytes) throw new HttpError(413, "Le fichier dépasse la taille maximale autorisée");
        String directory = PathUtil.normalize(req.getParameter("dir"));
        String name = req.getParameter("name");
        PathUtil.validateName(name);
        String destination = PathUtil.join(directory, name);
        Path temporary = Files.createTempFile(store.tempDirectory(), "browser-upload-", ".tmp");
        try (InputStream input = req.getInputStream()) {
            OnlyOfficeService.copyLimited(input, temporary, config.maxUploadBytes);
            clientFor(account.id, req).putFile(destination, temporary, req.getContentType());
        } finally {
            Files.deleteIfExists(temporary);
        }
        writeOk(resp);
    }

    private void createShare(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        String path = PathUtil.normalize(Json.string(body, "path", ""));
        String password = Json.string(body, "password", "");
        String expireDate = Json.string(body, "expireDate", "").trim();
        if (password.length() > 256) throw new HttpError(400, "Mot de passe de partage trop long");
        validateExpiration(expireDate);
        writeJson(resp, 200, clientFor(account.id, req).createPublicShare(path, password, expireDate));
    }

    private void batch(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        String operation = Json.string(body, "operation", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!"delete".equals(operation) && !"move".equals(operation) && !"copy".equals(operation)) {
            throw new HttpError(400, "Opération groupée inconnue");
        }
        Object rawPaths = body.get("paths");
        if (!(rawPaths instanceof List)) throw new HttpError(400, "La liste des éléments est obligatoire");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object value : (List<?>) rawPaths) {
            String path = PathUtil.normalize(value == null ? "" : String.valueOf(value));
            if ("/".equals(path)) throw new HttpError(400, "Le dossier racine ne peut pas être traité");
            unique.add(path);
            if (unique.size() > 200) throw new HttpError(400, "Trop d’éléments sélectionnés (maximum 200)");
        }
        if (unique.isEmpty()) throw new HttpError(400, "Aucun élément sélectionné");

        ArrayList<String> sorted = new ArrayList<>(unique);
        sorted.sort((left, right) -> {
            int length = Integer.compare(left.length(), right.length());
            return length == 0 ? left.compareTo(right) : length;
        });
        ArrayList<String> paths = new ArrayList<>();
        for (String candidate : sorted) {
            boolean covered = false;
            for (String parent : paths) {
                if (candidate.startsWith(parent + "/")) {
                    covered = true;
                    break;
                }
            }
            if (!covered) paths.add(candidate);
        }

        NextcloudClient client = clientFor(account.id, req);
        String collisionPolicy = Json.string(body, "collisionPolicy", "ask").trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("ask", "replace", "keep-both", "skip").contains(collisionPolicy)) {
            throw new HttpError(400, "Politique de conflit de fichier invalide");
        }
        String destination = "";
        if (!"delete".equals(operation)) {
            destination = PathUtil.normalize(Json.string(body, "destination", ""));
            Map<String, Object> target = client.stat(destination);
            if (!Boolean.TRUE.equals(target.get("isDirectory"))) {
                throw new HttpError(400, "La destination doit être un dossier Nextcloud");
            }
        }

        ArrayList<String> completed = new ArrayList<>();
        ArrayList<String> skipped = new ArrayList<>();
        ArrayList<Map<String, Object>> failures = new ArrayList<>();
        for (String path : paths) {
            try {
                if ("delete".equals(operation)) {
                    client.delete(path);
                } else {
                    String target = NextcloudClient.bulkDestination(path, destination);
                    target = client.resolveCollision(target, collisionPolicy);
                    if ("move".equals(operation)) client.move(path, target, "replace".equals(collisionPolicy));
                    else client.copy(path, target, "replace".equals(collisionPolicy));
                }
                for (String requested : sorted) {
                    if (requested.equals(path) || requested.startsWith(path + "/")) completed.add(requested);
                }
            } catch (HttpError | IllegalArgumentException e) {
                if (e instanceof HttpError && ((HttpError) e).status == 208) {
                    for (String requested : sorted) {
                        if (requested.equals(path) || requested.startsWith(path + "/")) skipped.add(requested);
                    }
                    continue;
                }
                for (String requested : sorted) {
                    if (!requested.equals(path) && !requested.startsWith(path + "/")) continue;
                    LinkedHashMap<String, Object> failure = new LinkedHashMap<>();
                    failure.put("path", requested);
                    failure.put("error", ServerI18n.localize(e.getMessage()));
                    failures.add(failure);
                }
            }
        }

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("destination", destination);
        result.put("completed", completed);
        result.put("skipped", skipped);
        result.put("failures", failures);
        result.put("ok", failures.isEmpty());
        writeJson(resp, failures.isEmpty() ? 200 : 207, result);
    }

    private void create(HttpServletRequest req, HttpServletResponse resp, AccountContext account) throws Exception {
        Map<String, Object> body = readJson(req);
        String path = PathUtil.normalize(Json.string(body, "path", ""));
        String kind = Json.string(body, "kind", "").toLowerCase();
        String collisionPolicy = Json.string(body, "collisionPolicy", "ask");
        NextcloudClient client = clientFor(account.id, req);
        String target;
        try { target = client.resolveCollision(path, collisionPolicy); }
        catch (HttpError e) {
            if (e.status != 208) throw e;
            LinkedHashMap<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("ok", true);
            skipped.put("skipped", true);
            skipped.put("path", path);
            writeJson(resp, 200, skipped);
            return;
        }
        if ("folder".equals(kind)) {
            if ("replace".equalsIgnoreCase(collisionPolicy) && target.equals(path)) {
                try {
                    Map<String, Object> existing = client.stat(path);
                    if (Boolean.TRUE.equals(existing.get("isDirectory"))) {
                        throw new HttpError(409, "Un dossier portant ce nom existe déjà");
                    }
                    throw new HttpError(409, "Un fichier portant ce nom existe déjà");
                } catch (HttpError e) {
                    if (e.status != 404) throw e;
                }
            }
            client.createDirectory(target);
        } else if ("docx".equals(kind) || "xlsx".equals(kind) || "pptx".equals(kind)
            || "odt".equals(kind) || "ods".equals(kind) || "odp".equals(kind)) {
            if (!PathUtil.extension(target).equals(kind)) throw new HttpError(400, "L’extension du document ne correspond pas au type choisi");
            byte[] content = templates.load(kind, Json.string(body, "templateId", ""), requestLanguage(req));
            client.putBytes(target, content, onlyoffice.contentTypeFor(target), "replace".equalsIgnoreCase(collisionPolicy));
        } else {
            throw new HttpError(400, "Type de création inconnu");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("path", target);
        result.put("renamed", !path.equals(target));
        writeJson(resp, 200, result);
    }

    private void handleAuthenticatedFile(HttpServletRequest req, HttpServletResponse resp, AccountContext account)
        throws Exception {
        String path = PathUtil.normalize(req.getParameter("path"));
        String disposition = "attachment".equals(req.getParameter("disposition")) ? "attachment" : "inline";
        streamNextcloudFile(clientFor(account.id, req), path, req.getHeader("Range"), disposition, resp);
    }

    private void handlePublicContent(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        TicketService.Ticket ticket = tickets.verify(req.getParameter("ticket"), "content");
        Profile profile = requiredProfile(ticket.accountId, ticket.profileId);
        streamNextcloudFile(new NextcloudClient(config, profile), ticket.path, req.getHeader("Range"), "inline", resp);
    }

    private void handlePublicCallback(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            TicketService.Ticket ticket = tickets.verify(req.getParameter("ticket"), "callback");
            Map<String, Object> body = readJson(req);
            Profile profile = requiredProfile(ticket.accountId, ticket.profileId);
            OfficeSettings office = profile.office.resolve(config);
            String authorization = req.getHeader(office.jwtHeader);
            if ((authorization == null || authorization.isBlank()) && !"Authorization".equalsIgnoreCase(office.jwtHeader)) {
                authorization = req.getHeader("Authorization");
            }
            onlyoffice.processCallback(ticket, profile, office, body, authorization);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("error", 0);
            writeJson(resp, 200, result);
        } catch (GeneralSecurityException e) {
            writeError(resp, 403, "Callback du serveur bureautique non authentifié");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Échec d’enregistrement d’un document bureautique dans Nextcloud", e);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("error", 1);
            writeJson(resp, 200, result);
        }
    }

    private void streamNextcloudFile(NextcloudClient client, String path, String range, String disposition, HttpServletResponse resp)
        throws Exception {
        HttpResponse<InputStream> remote = client.get(path, range);
        resp.setStatus(remote.statusCode());
        copyHeader(remote, resp, "Content-Length");
        copyHeader(remote, resp, "Content-Range");
        copyHeader(remote, resp, "Accept-Ranges");
        copyHeader(remote, resp, "ETag");
        copyHeader(remote, resp, "Last-Modified");
        resp.setContentType(safeContentType(path));
        resp.setHeader("Cache-Control", "private, no-store");
        resp.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
        resp.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");
        resp.setHeader("Content-Disposition", disposition + "; filename*=UTF-8''" + rfc5987(PathUtil.fileName(path)));
        try (InputStream input = remote.body()) {
            input.transferTo(resp.getOutputStream());
        }
    }

    private void streamThumbnail(NextcloudClient client, String fileId, HttpServletResponse resp)
        throws Exception {
        HttpResponse<InputStream> remote = client.thumbnail(fileId);
        String contentType = remote.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (!contentType.startsWith("image/")) {
            try (InputStream ignored = remote.body()) {}
            throw new HttpError(502, "Nextcloud n’a pas renvoyé une image miniature");
        }
        resp.setStatus(200);
        copyHeader(remote, resp, "Content-Length");
        copyHeader(remote, resp, "ETag");
        copyHeader(remote, resp, "Last-Modified");
        resp.setContentType(contentType);
        resp.setHeader("Cache-Control", "private, max-age=3600");
        resp.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
        resp.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        resp.setHeader("Content-Disposition", "inline");
        try (InputStream input = remote.body()) {
            input.transferTo(resp.getOutputStream());
        }
    }

    private void streamTalkGif(NextcloudTalkClient client, String url, HttpServletResponse resp) throws Exception {
        HttpResponse<InputStream> remote = client.getGif(url);
        String contentType = remote.headers().firstValue("Content-Type").orElse("image/gif").toLowerCase();
        if (!contentType.startsWith("image/")) {
            try (InputStream ignored = remote.body()) {}
            throw new HttpError(502, "Nextcloud n’a pas renvoyé une image GIF");
        }
        resp.setStatus(200);
        copyHeader(remote, resp, "Content-Length");
        copyHeader(remote, resp, "ETag");
        resp.setContentType(contentType);
        resp.setHeader("Cache-Control", "private, max-age=86400");
        resp.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
        resp.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        resp.setHeader("Content-Disposition", "inline");
        try (InputStream input = remote.body()) { input.transferTo(resp.getOutputStream()); }
    }

    private void streamVersion(NextcloudClient client, String fileId, String versionId, String rawName,
        HttpServletResponse resp) throws Exception {
        String name = rawName == null || rawName.isBlank() ? "version-" + versionId : PathUtil.fileName("/" + rawName);
        HttpResponse<InputStream> remote = client.getVersion(fileId, versionId);
        resp.setStatus(200);
        copyHeader(remote, resp, "Content-Length");
        copyHeader(remote, resp, "ETag");
        copyHeader(remote, resp, "Last-Modified");
        resp.setContentType(safeContentType(name));
        resp.setHeader("Cache-Control", "private, no-store");
        resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + rfc5987(name));
        try (InputStream input = remote.body()) { input.transferTo(resp.getOutputStream()); }
    }

    @SuppressWarnings("unchecked")
    private void streamArchive(NextcloudClient client, HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String directory = PathUtil.normalize(req.getParameter("directory"));
        String rawFiles = req.getParameter("files");
        if (rawFiles == null || rawFiles.isBlank() || rawFiles.length() > 65536) {
            throw new HttpError(400, "Liste de fichiers ZIP invalide");
        }
        Object parsed = Json.parse(rawFiles);
        if (!(parsed instanceof List)) throw new HttpError(400, "Liste de fichiers ZIP invalide");
        ArrayList<String> names = new ArrayList<>();
        for (Object value : (List<Object>) parsed) names.add(String.valueOf(value));
        HttpResponse<InputStream> remote = client.getZip(directory, names);
        resp.setStatus(200);
        copyHeader(remote, resp, "Content-Length");
        resp.setContentType("application/zip");
        resp.setHeader("Cache-Control", "private, no-store");
        resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''Cloud-files.zip");
        try (InputStream input = remote.body()) { input.transferTo(resp.getOutputStream()); }
    }

    private Map<String, Object> diagnostics(AccountContext account, HttpServletRequest req) throws Exception {
        NextcloudClient client = clientFor(account.id, req);
        ArrayList<Map<String, Object>> checks = new ArrayList<>();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("connectorVersion", Version.VALUE);
        result.put("profileId", profileIdFromRequest(req));
        result.put("sharedStorage", config.sharedStorage);
        result.put("remoteBackgrounds", config.remoteBackgroundsAllowed);
        try {
            Map<String, Object> capabilities = client.capabilities();
            result.put("nextcloud", capabilities);
            checks.add(checkResult("nextcloudCapabilities", true, Json.string(capabilities, "serverVersion", "")));
        } catch (Exception e) {
            checks.add(checkResult("nextcloudCapabilities", false, e.getMessage()));
        }
        try {
            result.put("quota", client.quota());
            checks.add(checkResult("webdav", true, "OK"));
        } catch (Exception e) {
            checks.add(checkResult("webdav", false, e.getMessage()));
        }
        Profile profile = requiredProfile(account.id, profileIdFromRequest(req));
        OfficeSettings office = profile.office.resolve(config);
        LinkedHashMap<String, Object> officeInfo = new LinkedHashMap<>();
        officeInfo.put("provider", office.provider);
        officeInfo.put("displayName", office.displayName);
        officeInfo.put("url", office.publicUrl);
        officeInfo.put("securityMode", office.securityMode);
        result.put("office", officeInfo);
        result.put("mailLimits", mailLimits(account));
        result.put("checks", checks);
        result.put("status", checks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("ok"))) ? "ok" : "degraded");
        return result;
    }

    private static Map<String, Object> checkResult(String name, boolean ok, String detail) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("ok", ok);
        result.put("detail", detail == null ? "" : detail);
        return result;
    }

    private Map<String, Object> mailLimits(AccountContext account) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        long bytes = account.maxMessageBytes > 0L ? account.maxMessageBytes : config.fallbackAttachmentBytes;
        int count = account.maxAttachmentCount > 0 ? account.maxAttachmentCount : config.fallbackAttachmentCount;
        result.put("maxAttachments", count);
        result.put("maxBytes", bytes);
        result.put("source", account.maxMessageBytes > 0L || account.maxAttachmentCount > 0 ? "zimbra" : "fallback");
        return result;
    }

    private static void copyHeader(HttpResponse<?> remote, HttpServletResponse local, String name) {
        remote.headers().firstValue(name).ifPresent(value -> local.setHeader(name, value));
    }

    private Map<String, Object> publicProfile(AccountContext account) throws IOException, GeneralSecurityException {
        return publicProfile(account, store.loadProfiles(account.id).orElse(ProfileSet.empty()));
    }

    private Map<String, Object> publicProfile(AccountContext account, Optional<Profile> saved) {
        ProfileSet profiles = saved.map(profile -> new ProfileSet(profile.id, List.of(profile))).orElse(ProfileSet.empty());
        return publicProfile(account, profiles);
    }

    private Map<String, Object> publicProfile(AccountContext account, ProfileSet profiles) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        Optional<Profile> saved = profiles.active();
        result.put("configured", saved.isPresent());
        result.put("accountMode", config.nextcloudAccountMode);
        result.put("activationAvailable", config.managedAccountsEnabled() && profiles.profiles.isEmpty());
        result.put("activeProfileId", profiles.activeProfileId);
        result.put("maxAccounts", ProfileSet.MAX_PROFILES);
        result.put("canAddAccount", !profiles.profiles.isEmpty() && profiles.profiles.size() < ProfileSet.MAX_PROFILES);
        result.put("talkEnabled", saved.map(profile -> profiles.talkEnabled(profile.id)).orElse(false));
        result.put("talkAnyEnabled", profiles.anyTalkEnabled());
        result.put("nextcloudUrl", saved.map(profile -> profile.nextcloudUrl).orElse(""));
        result.put("username", saved.map(profile -> profile.username).orElse(account.email));
        result.put("label", saved.map(profile -> profile.label).orElse(""));
        result.put("managed", saved.map(profile -> profile.managed).orElse(false));
        result.put("passwordSet", saved.map(profile -> !profile.appPassword.isBlank()).orElse(false));
        OfficeSettings activeOffice = saved.map(profile -> profile.office.resolve(config))
            .orElseGet(() -> OfficeSettings.global(config));
        String activeOfficeMode = saved.map(profile -> profile.office.mode).orElse(OfficeProfile.GLOBAL);
        java.util.ArrayList<Map<String, Object>> accounts = new java.util.ArrayList<>();
        for (Profile profile : profiles.profiles) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("id", profile.id);
            item.put("label", profile.label);
            item.put("nextcloudUrl", profile.nextcloudUrl);
            item.put("username", profile.username);
            item.put("managed", profile.managed);
            item.put("passwordSet", !profile.appPassword.isBlank());
            item.put("active", profile.id.equals(profiles.activeProfileId));
            item.put("talkEnabled", profiles.talkEnabled(profile.id));
            OfficeSettings profileOffice = profile.office.resolve(config);
            item.put("officeMode", profile.office.mode);
            item.put("officeProvider", profileOffice.provider);
            item.put("officeLabel", profileOffice.displayName);
            item.put("officeUrl", profileOffice.publicUrl);
            item.put("officeSecurityMode", profileOffice.securityMode);
            item.put("officeJwtHeader", profileOffice.jwtHeader);
            item.put("officeJwtSecretSet", profileOffice.jwtEnabled() && !profileOffice.jwtSecret.isBlank());
            accounts.add(item);
        }
        result.put("accounts", accounts);
        result.put("managedNextcloudUrl", config.managedAccountsEnabled() ? config.managedNextcloudUrl : "");
        result.put("managedQuota", config.managedAccountsEnabled() ? config.managedQuota : "");
        result.put("officeMode", activeOfficeMode);
        result.put("officeProvider", activeOffice.provider);
        result.put("officeLabel", activeOffice.displayName);
        result.put("officeUrl", activeOffice.publicUrl);
        result.put("officeSecurityMode", activeOffice.securityMode);
        result.put("officeJwtHeader", activeOffice.jwtHeader);
        result.put("officeJwtSecretSet", activeOffice.jwtEnabled() && !activeOffice.jwtSecret.isBlank());
        result.put("defaultOfficeProvider", config.officeProvider);
        result.put("defaultOfficeLabel", config.officeDisplayName);
        result.put("defaultOfficeUrl", config.officePublicUrl);
        result.put("defaultOfficeSecurityMode", config.officeSecurityMode);
        result.put("defaultOfficeJwtHeader", config.officeJwtHeader);
        result.put("defaultLanguage", config.defaultLanguage);
        result.put("remoteBackgroundsAllowed", config.remoteBackgroundsAllowed);
        result.put("sharedStorage", config.sharedStorage);
        result.put("uploadChunkBytes", config.uploadChunkBytes);
        result.put("maxUploadBytes", config.maxUploadBytes);
        result.put("connectorVersion", Version.VALUE);
        return result;
    }

    private NextcloudClient clientFor(String accountId, HttpServletRequest req) throws Exception {
        return new NextcloudClient(config, requiredProfile(accountId, profileIdFromRequest(req)));
    }

    private NextcloudTalkClient talkClientFor(String accountId, HttpServletRequest req) throws Exception {
        ProfileSet profiles = store.loadProfiles(accountId).orElse(ProfileSet.empty());
        Profile profile = profiles.byId(profileIdFromRequest(req))
            .orElseThrow(() -> new HttpError(409, "Configurez d’abord votre compte Nextcloud"));
        if (!profiles.talkEnabled(profile.id)) throw new HttpError(409, "Le Chat Nextcloud Talk est désactivé pour ce compte");
        return new NextcloudTalkClient(config, profile);
    }

    private Map<String, Object> talkOverview(AccountContext account) throws Exception {
        ProfileSet profiles = store.loadProfiles(account.id).orElse(ProfileSet.empty());
        if (!profiles.anyTalkEnabled()) throw new HttpError(409, "Le Chat Nextcloud Talk est désactivé");
        ArrayList<Map<String, Object>> accounts = new ArrayList<>();
        long totalUnread = 0L;
        boolean available = false;
        for (Profile profile : profiles.profiles) {
            if (!profiles.talkEnabled(profile.id)) continue;
            try {
                Map<String, Object> item = talkAccountOverview(profile);
                available = available || Json.bool(item, "available", false);
                totalUnread += Math.max(0L, Json.longValue(item, "unread", 0L));
                accounts.add(item);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                LinkedHashMap<String, Object> item = talkAccountIdentity(profile);
                item.put("available", false);
                item.put("conversations", List.of());
                item.put("unread", 0L);
                item.put("error", "Nextcloud Talk indisponible pour ce compte");
                accounts.add(item);
            }
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("available", available);
        result.put("unread", totalUnread);
        result.put("accounts", accounts);
        result.put("pollAfterSeconds", 20L);
        return result;
    }

    private Map<String, Object> talkAccountOverview(Profile profile) throws Exception {
        LinkedHashMap<String, Object> item = talkAccountIdentity(profile);
        NextcloudTalkClient client = new NextcloudTalkClient(config, profile);
        Map<String, Object> status = client.status();
        boolean available = Json.bool(status, "available", false);
        item.putAll(status);
        ArrayList<Map<String, Object>> conversations = new ArrayList<>();
        long unread = 0L;
        if (available) {
            conversations.addAll(client.conversations());
            for (Map<String, Object> conversation : conversations) {
                unread += Math.max(0L, Json.longValue(conversation, "unreadMessages", 0L));
            }
        }
        item.put("conversations", conversations);
        item.put("unread", unread);
        return item;
    }

    private static LinkedHashMap<String, Object> talkAccountIdentity(Profile profile) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("profileId", profile.id);
        item.put("label", profile.label.isBlank() ? profile.username : profile.label);
        item.put("server", profile.nextcloudUrl);
        item.put("username", profile.username);
        return item;
    }

    private Profile requiredProfile(String accountId, String profileId) throws IOException, GeneralSecurityException, HttpError {
        return store.load(accountId, profileId)
            .orElseThrow(() -> new HttpError(409, "Configurez d’abord votre compte Nextcloud"));
    }

    private static String profileIdFromRequest(HttpServletRequest req) {
        String profileId = req.getHeader("X-Nextcloud-Profile");
        if (profileId == null || profileId.isBlank()) profileId = req.getParameter("profileId");
        return profileId == null ? "" : profileId.trim();
    }

    private void ensureInitialized() throws HttpError {
        if (config != null) return;
        synchronized (this) {
            if (config != null) return;
            try {
                AppConfig loadedConfig = AppConfig.load();
                CredentialStore loadedStore = new CredentialStore(loadedConfig.storageDirectory);
                TicketService loadedTickets = new TicketService(loadedConfig);
                OnlyOfficeService loadedOnlyoffice = new OnlyOfficeService(loadedConfig, loadedStore, loadedTickets);
                NextcloudProvisioningService loadedProvisioning = new NextcloudProvisioningService(loadedConfig, loadedStore);
                NextcloudLoginFlow loadedLoginFlow = new NextcloudLoginFlow(loadedConfig);
                DocumentTemplateService loadedTemplates = new DocumentTemplateService(loadedConfig);
                RequestLimiter loadedLimiter = new RequestLimiter(
                    loadedConfig.maxConcurrentRequests, loadedConfig.maxRequestsPerMinute
                );
                store = loadedStore;
                tickets = loadedTickets;
                onlyoffice = loadedOnlyoffice;
                provisioning = loadedProvisioning;
                loginFlow = loadedLoginFlow;
                templates = loadedTemplates;
                limiter = loadedLimiter;
                ServerI18n.setDefaultLanguage(loadedConfig.defaultLanguage);
                config = loadedConfig;
            } catch (Exception | LinkageError e) {
                LOGGER.log(Level.SEVERE, "Configuration du connecteur Nextcloud indisponible", e);
                String detail = e.getMessage();
                if (detail == null || detail.isBlank()) detail = e.getClass().getSimpleName();
                throw new HttpError(503, "Configuration serveur du connecteur invalide : " + detail, e);
            }
        }
    }

    private AccountContext authenticate(HttpServletRequest req, HttpServletResponse resp) throws HttpError {
        try {
            AuthToken token = AuthUtil.getAuthTokenFromHttpReq(req, resp, false, true);
            if (token == null) throw new HttpError(401, "Session Zimbra expirée");
            Account account = Provisioning.getInstance().getAccountById(token.getAccountId());
            if (account == null) throw new HttpError(401, "Compte Zimbra introuvable");
            long maxMessageBytes = reflectedAccountLong(account, -1L,
                "zimbraMailMaxMessageSize", "zimbraMailMaxSize", "zimbraMtaMaxMessageSize");
            int maxAttachmentCount = (int) Math.min(Integer.MAX_VALUE,
                reflectedAccountLong(account, -1L, "zimbraMailMaxAttachments"));
            return new AccountContext(token.getAccountId(), account.getName(), maxMessageBytes, maxAttachmentCount);
        } catch (HttpError e) {
            throw e;
        } catch (Exception e) {
            throw new HttpError(401, "Session Zimbra invalide", e);
        }
    }

    private static void requireZimletRequest(HttpServletRequest req) throws HttpError {
        if (!"com_nextcloud_connector".equals(req.getHeader("X-Zimbra-Zimlet"))) {
            throw new HttpError(403, "Requête refusée");
        }
    }

    private static long reflectedAccountLong(Account account, long fallback, String... names) {
        try {
            java.lang.reflect.Method getter;
            try { getter = account.getClass().getMethod("getAttr", String.class); }
            catch (NoSuchMethodException e) { getter = null; }
            if (getter == null) return fallback;
            for (String name : names) {
                Object value = getter.invoke(account, name);
                if (value == null || String.valueOf(value).isBlank()) continue;
                try {
                    long parsed = Long.parseLong(String.valueOf(value).trim());
                    if (parsed > 0L) return parsed;
                } catch (NumberFormatException ignored) {}
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return fallback;
    }

    private static Map<String, Object> readJson(HttpServletRequest req) throws IOException, HttpError {
        long length = req.getContentLengthLong();
        if (length > MAX_JSON_BYTES) throw new HttpError(413, "Requête JSON trop volumineuse");
        try (InputStream input = req.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_JSON_BYTES) throw new HttpError(413, "Requête JSON trop volumineuse");
                output.write(buffer, 0, count);
            }
            try { return Json.parseObject(output.toString(StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException e) { throw new HttpError(400, "JSON invalide", e); }
        }
    }

    private static byte[] readBinary(HttpServletRequest req, long maximum) throws IOException, HttpError {
        long announced = req.getContentLengthLong();
        if (announced > maximum) throw new HttpError(413, "Morceau d’envoi trop volumineux");
        try (InputStream input = req.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximum) throw new HttpError(413, "Morceau d’envoi trop volumineux");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static int queryInt(HttpServletRequest req, String name, int fallback, int minimum, int maximum)
        throws HttpError {
        String raw = req.getParameter(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new HttpError(400, "Paramètre numérique invalide : " + name);
        }
    }

    private static long queryLong(HttpServletRequest req, String name, long fallback, long minimum, long maximum)
        throws HttpError {
        String raw = req.getParameter(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            long value = Long.parseLong(raw.trim());
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new HttpError(400, "Paramètre numérique invalide : " + name);
        }
    }

    private static void validateExpiration(String expireDate) throws HttpError {
        if (expireDate == null || expireDate.isBlank()) return;
        try {
            LocalDate expiration = LocalDate.parse(expireDate.trim());
            if (expiration.isBefore(LocalDate.now())) throw new HttpError(400, "La date d’expiration est déjà passée");
        } catch (java.time.format.DateTimeParseException e) {
            throw new HttpError(400, "Date d’expiration invalide");
        }
    }

    private static String route(HttpServletRequest req) {
        String uri = req.getRequestURI();
        int start = uri.indexOf(URI_PREFIX);
        if (start < 0) return "/";
        String route = uri.substring(start + URI_PREFIX.length());
        return route.isEmpty() ? "/" : route;
    }

    private static String requestLanguage(HttpServletRequest req) {
        String language = req.getHeader("X-Zimbra-Zimlet-Language");
        if (language == null || language.isBlank()) language = req.getParameter("lang");
        if (language == null || language.isBlank()) language = req.getHeader("Accept-Language");
        return language;
    }

    private static void secureHeaders(HttpServletResponse resp) {
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Referrer-Policy", "no-referrer");
        resp.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private static void writeOk(HttpServletResponse resp) throws IOException {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        writeJson(resp, 200, result);
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        if (resp.isCommitted()) return;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("error", ServerI18n.localize(message));
        writeJson(resp, status, result);
    }

    private static void writeJson(HttpServletResponse resp, int status, Map<String, Object> body) throws IOException {
        resp.setStatus(status);
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(Json.stringify(body));
    }

    private static String rfc5987(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%7E", "~");
    }

    private static String safeContentType(String path) {
        switch (PathUtil.extension(path)) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            case "avif": return "image/avif";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "ogv": return "video/ogg";
            case "mov": return "video/quicktime";
            case "m4v": return "video/x-m4v";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": case "oga": return "audio/ogg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "flac": return "audio/flac";
            case "pdf": return "application/pdf";
            case "txt": case "md": case "log": case "json": case "xml": case "csv":
            case "ini": case "yaml": case "yml": return "text/plain; charset=UTF-8";
            case "html": case "htm": return "text/html; charset=UTF-8";
            case "epub": return "application/epub+zip";
            case "fb2": return "application/x-fictionbook+xml";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "odt": return "application/vnd.oasis.opendocument.text";
            case "rtf": return "application/rtf";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "odp": return "application/vnd.oasis.opendocument.presentation";
            default: return "application/octet-stream";
        }
    }
}
