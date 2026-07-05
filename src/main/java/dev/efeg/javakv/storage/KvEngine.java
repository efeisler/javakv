package dev.efeg.javakv.storage;

import dev.efeg.javakv.storage.memtable.MemTable;
import dev.efeg.javakv.storage.wal.WalReader;
import dev.efeg.javakv.storage.wal.WalRecord;
import dev.efeg.javakv.storage.wal.WalWriter;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Facade the network layer talks to. Every mutation is appended to the write-ahead log before
 * it is applied to the MemTable, under a single writer lock; on startup the WAL is replayed to
 * rebuild the MemTable. Later phases add SSTables and compaction behind this same interface.
 */
public final class KvEngine implements Closeable {

    private static final String WAL_FILE_NAME = "wal.log";

    private final MemTable memTable = new MemTable();
    private final WalWriter walWriter;
    private final ReentrantLock writeLock = new ReentrantLock();

    public KvEngine(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path walFile = dataDir.resolve(WAL_FILE_NAME);
        replay(walFile);
        this.walWriter = new WalWriter(walFile);
    }

    private void replay(Path walFile) throws IOException {
        for (WalRecord record : WalReader.readAll(walFile)) {
            memTable.put(record.key(), record.isTombstone() ? Record.tombstone() : toRecord(record));
        }
    }

    private static Record toRecord(WalRecord walRecord) {
        return walRecord.expiryEpochMillis() == 0
                ? Record.of(walRecord.value())
                : Record.ofWithExpiry(walRecord.value(), walRecord.expiryEpochMillis());
    }

    public byte[] get(byte[] key) {
        Record record = memTable.get(key);
        if (record == null || record.isTombstone()) {
            return null;
        }
        return record.value();
    }

    public void put(byte[] key, byte[] value, long expiryEpochMillis) throws IOException {
        writeLock.lock();
        try {
            walWriter.append(new WalRecord(key, value, expiryEpochMillis));
            Record record = expiryEpochMillis == 0 ? Record.of(value) : Record.ofWithExpiry(value, expiryEpochMillis);
            memTable.put(key, record);
        } finally {
            writeLock.unlock();
        }
    }

    public boolean delete(byte[] key) throws IOException {
        writeLock.lock();
        try {
            Record existing = memTable.get(key);
            boolean existed = existing != null && !existing.isTombstone();
            walWriter.append(new WalRecord(key, null, 0));
            memTable.put(key, Record.tombstone());
            return existed;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        walWriter.close();
    }
}
