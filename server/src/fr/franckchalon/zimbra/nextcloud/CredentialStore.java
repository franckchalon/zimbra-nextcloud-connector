package fr.franckchalon.zimbra.nextcloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

final class CredentialStore {
    private final Path root;
    private final Path profiles;
    private final Path lockFile;
    private final byte[] key;

    CredentialStore(Path root) throws IOException {
        this.root = root;
        this.profiles = root.resolve("profiles");
        this.lockFile = root.resolve("profiles.lock");
        Files.createDirectories(profiles);
        setDirectoryPermissions(root);
        setDirectoryPermissions(profiles);
        byte[] loadedKey;
        try (FileChannel channel = lockChannel(); FileLock ignored = channel.lock()) {
            loadedKey = loadOrCreateKey(root.resolve("master.key"));
        }
        this.key = loadedKey;
    }

    Optional<Profile> load(String accountId) throws IOException, GeneralSecurityException {
        return loadProfiles(accountId).flatMap(ProfileSet::active);
    }

    Optional<Profile> load(String accountId, String profileId) throws IOException, GeneralSecurityException {
        return loadProfiles(accountId).flatMap(profiles -> profiles.byId(profileId));
    }

    Optional<ProfileSet> loadProfiles(String accountId) throws IOException, GeneralSecurityException {
        Path path = profilePath(accountId);
        if (!Files.exists(path)) return Optional.empty();
        String encoded = Files.readString(path, StandardCharsets.UTF_8).trim();
        String clear = Crypto.decrypt(key, accountId, encoded);
        return Optional.of(ProfileSet.fromStorageMap(Json.parseObject(clear)));
    }

    synchronized void save(String accountId, Profile profile) throws IOException, GeneralSecurityException {
        try (FileChannel channel = lockChannel(); FileLock ignored = channel.lock()) {
            ProfileSet current = loadProfiles(accountId).orElse(ProfileSet.empty()).upsert(profile, true);
            saveProfilesUnlocked(accountId, current);
        }
    }

    synchronized void saveProfiles(String accountId, ProfileSet profileSet) throws IOException, GeneralSecurityException {
        try (FileChannel channel = lockChannel(); FileLock ignored = channel.lock()) {
            saveProfilesUnlocked(accountId, profileSet);
        }
    }

    private void saveProfilesUnlocked(String accountId, ProfileSet profileSet) throws IOException, GeneralSecurityException {
        String encrypted = Crypto.encrypt(key, accountId, Json.stringify(profileSet.toStorageMap()));
        Path target = profilePath(accountId);
        Path temporary = Files.createTempFile(profiles, ".profile-", ".tmp");
        Files.writeString(temporary, encrypted + System.lineSeparator(), StandardCharsets.UTF_8);
        setFilePermissions(temporary);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        setFilePermissions(target);
    }

    synchronized void delete(String accountId) throws IOException {
        try (FileChannel channel = lockChannel(); FileLock ignored = channel.lock()) {
            Files.deleteIfExists(profilePath(accountId));
        }
    }

    synchronized void delete(String accountId, String profileId) throws IOException, GeneralSecurityException {
        try (FileChannel channel = lockChannel(); FileLock ignored = channel.lock()) {
            ProfileSet current = loadProfiles(accountId).orElse(ProfileSet.empty()).remove(profileId);
            if (current.profiles.isEmpty()) Files.deleteIfExists(profilePath(accountId));
            else saveProfilesUnlocked(accountId, current);
        }
    }

    synchronized void select(String accountId, String profileId) throws IOException, GeneralSecurityException {
        try (FileChannel channel = lockChannel(); FileLock ignored = channel.lock()) {
            ProfileSet current = loadProfiles(accountId).orElse(ProfileSet.empty()).select(profileId);
            saveProfilesUnlocked(accountId, current);
        }
    }

    Path tempDirectory() throws IOException {
        Path temp = root.resolve("tmp");
        Files.createDirectories(temp);
        setDirectoryPermissions(temp);
        return temp;
    }

    private Path profilePath(String accountId) {
        return profiles.resolve(Crypto.sha256Hex(accountId) + ".enc");
    }

    private FileChannel lockChannel() throws IOException {
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        setFilePermissions(lockFile);
        return channel;
    }

    private static byte[] loadOrCreateKey(Path path) throws IOException {
        if (!Files.exists(path)) {
            byte[] generated = Crypto.randomBytes(32);
            Path temp = Files.createTempFile(path.getParent(), ".master-key-", ".tmp");
            Files.writeString(temp, Base64.getEncoder().encodeToString(generated) + System.lineSeparator(), StandardCharsets.US_ASCII);
            setFilePermissions(temp);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path); }
            catch (java.nio.file.FileAlreadyExistsException e) { Files.deleteIfExists(temp); }
            setFilePermissions(path);
            if (Files.exists(path)) {
                byte[] existing = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.US_ASCII).trim());
                if (existing.length == 32) return existing;
            }
            return generated;
        }
        byte[] decoded = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.US_ASCII).trim());
        if (decoded.length != 32) throw new IOException("La clé maître doit contenir 32 octets");
        setFilePermissions(path);
        return decoded;
    }

    private static void setDirectoryPermissions(Path path) {
        setPermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }

    private static void setFilePermissions(Path path) {
        setPermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try { Files.setPosixFilePermissions(path, permissions); } catch (UnsupportedOperationException | IOException ignored) {}
    }
}
