package fr.franckchalon.zimbra.nextcloud;

final class HttpError extends Exception {
    final int status;

    HttpError(int status, String message) {
        super(message);
        this.status = status;
    }

    HttpError(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}

