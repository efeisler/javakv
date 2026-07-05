package dev.efeg.javakv.protocol;

/** {@code expirySeconds == 0} means no TTL was requested. */
public record SetCommand(byte[] key, byte[] value, long expirySeconds) implements Command {
}
