package dev.efeg.javakv.protocol;

/** A malformed request. The message is sent back to the client verbatim after an {@code -ERR } prefix. */
public final class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }
}
