package dev.efeg.javakv.storage;

import dev.efeg.javakv.storage.sstable.SSTableReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, newest-first, copy-on-write list of the live SSTables. A lookup checks tables in
 * order and stops at the first hit (which may be a tombstone) — reads never need a lock.
 */
public final class SSTableSet {

    private final AtomicReference<List<SSTableReader>> readers = new AtomicReference<>(List.of());

    public Record get(byte[] key) throws IOException {
        for (SSTableReader reader : readers.get()) {
            Record record = reader.get(key);
            if (record != null) {
                return record;
            }
        }
        return null;
    }

    /** Prepends {@code reader} as the newest table; called once per newly published SSTable. */
    public void addNewest(SSTableReader reader) {
        readers.updateAndGet(current -> {
            List<SSTableReader> updated = new ArrayList<>(current.size() + 1);
            updated.add(reader);
            updated.addAll(current);
            return List.copyOf(updated);
        });
    }

    public void closeAll() throws IOException {
        for (SSTableReader reader : readers.get()) {
            reader.close();
        }
    }
}
