package dev.efeg.javakv.storage.wal;

/** One decoded WAL entry. {@code value == null} means the entry is a tombstone (delete). */
public record WalRecord(byte[] key, byte[] value, long expiryEpochMillis) {

    public boolean isTombstone() {
        return value == null;
    }
}
