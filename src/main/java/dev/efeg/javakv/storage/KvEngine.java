package dev.efeg.javakv.storage;

import dev.efeg.javakv.storage.memtable.MemTable;

/**
 * Facade the network layer talks to. Currently backed by an in-memory MemTable only;
 * later phases add a write-ahead log, SSTables and compaction behind this same interface.
 */
public final class KvEngine {

    private final MemTable memTable = new MemTable();

    public byte[] get(byte[] key) {
        Record record = memTable.get(key);
        if (record == null || record.isTombstone()) {
            return null;
        }
        return record.value();
    }

    public void put(byte[] key, byte[] value, long expiryEpochMillis) {
        Record record = expiryEpochMillis == 0 ? Record.of(value) : Record.ofWithExpiry(value, expiryEpochMillis);
        memTable.put(key, record);
    }

    public boolean delete(byte[] key) {
        Record existing = memTable.get(key);
        boolean existed = existing != null && !existing.isTombstone();
        memTable.put(key, Record.tombstone());
        return existed;
    }
}
