package fr.franckchalon.zimbra.nextcloud;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Small in-process guard against one account exhausting mailboxd worker threads. */
final class RequestLimiter {
    static final class Lease implements AutoCloseable {
        private final Semaphore semaphore;
        private boolean closed;

        Lease(Semaphore semaphore) { this.semaphore = semaphore; }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                semaphore.release();
            }
        }
    }

    private static final class Bucket {
        final ArrayDeque<Long> requests = new ArrayDeque<>();
        volatile long lastSeen;
    }

    private final Semaphore concurrent;
    private final int perMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    RequestLimiter(int maxConcurrent, int perMinute) {
        this.concurrent = new Semaphore(maxConcurrent, true);
        this.perMinute = perMinute;
    }

    Lease enter(String accountId) throws HttpError, InterruptedException {
        long now = Instant.now().toEpochMilli();
        Bucket bucket = buckets.computeIfAbsent(accountId, ignored -> new Bucket());
        synchronized (bucket) {
            while (!bucket.requests.isEmpty() && bucket.requests.peekFirst() < now - 60_000L) {
                bucket.requests.removeFirst();
            }
            if (bucket.requests.size() >= perMinute) {
                throw new HttpError(429, "Trop de requêtes Nextcloud pour ce compte. Réessayez dans une minute.");
            }
            bucket.requests.addLast(now);
            bucket.lastSeen = now;
        }
        if (!concurrent.tryAcquire(5, TimeUnit.SECONDS)) {
            throw new HttpError(503, "Le connecteur Nextcloud est momentanément occupé");
        }
        if ((now / 60_000L) % 15L == 0L) {
            buckets.entrySet().removeIf(entry -> entry.getValue().lastSeen < now - 3_600_000L);
        }
        return new Lease(concurrent);
    }
}
