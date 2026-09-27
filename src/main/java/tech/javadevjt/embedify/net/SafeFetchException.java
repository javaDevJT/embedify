package tech.javadevjt.embedify.net;

/** A deliberately URL-free error from an outbound fetch. */
public final class SafeFetchException extends RuntimeException {
    public enum Kind {
        INVALID_INPUT,
        BUSY,
        UPSTREAM
    }

    private final Kind kind;

    SafeFetchException(Kind kind) {
        super(switch (kind) {
            case INVALID_INPUT -> "The URL is invalid or not allowed.";
            case BUSY -> "The upstream fetch limit is busy.";
            case UPSTREAM -> "The upstream resource could not be fetched.";
        });
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
