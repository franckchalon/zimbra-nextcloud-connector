package fr.franckchalon.zimbra.nextcloud;

final class AccountContext {
    final String id;
    final String email;
    final long maxMessageBytes;
    final int maxAttachmentCount;

    AccountContext(String id, String email) {
        this(id, email, -1L, -1);
    }

    AccountContext(String id, String email, long maxMessageBytes, int maxAttachmentCount) {
        this.id = id;
        this.email = email;
        this.maxMessageBytes = maxMessageBytes;
        this.maxAttachmentCount = maxAttachmentCount;
    }
}
