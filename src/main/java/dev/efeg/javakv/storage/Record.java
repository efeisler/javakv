package dev.efeg.javakv.storage;

import java.util.Objects;

/** A stored value, a delete marker (tombstone), or a value with an optional expiry. */
public final class Record {

    private final byte[] value;
    private final long expiryEpochMillis;

    private Record(byte[] value, long expiryEpochMillis) {
        this.value = value;
        this.expiryEpochMillis = expiryEpochMillis;
    }

    public static Record of(byte[] value) {
        return new Record(Objects.requireNonNull(value), 0L);
    }

    public static Record ofWithExpiry(byte[] value, long expiryEpochMillis) {
        return new Record(Objects.requireNonNull(value), expiryEpochMillis);
    }

    public static Record tombstone() {
        return new Record(null, 0L);
    }

    public boolean isTombstone() {
        return value == null;
    }

    public byte[] value() {
        return value;
    }

    public long expiryEpochMillis() {
        return expiryEpochMillis;
    }

    public boolean isExpired(long nowEpochMillis) {
        return expiryEpochMillis != 0 && expiryEpochMillis <= nowEpochMillis;
    }
}
