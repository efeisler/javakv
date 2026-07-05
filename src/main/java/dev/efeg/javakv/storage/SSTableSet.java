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
            if (!reader.tryAcquire()) {
                continue; // being retired concurrently; a replacement already covers its data
            }
            try {
                Record record = reader.get(key);
                if (record != null) {
                    return record;
                }
            } finally {
                reader.release();
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

    /** Current live tables, newest first. */
    public List<SSTableReader> snapshot() {
        return readers.get();
    }

    /**
     * Atomically replaces every table in {@code mergedFrom} with {@code merged}, preserving any
     * table that was concurrently added (e.g. by a flush) after {@code mergedFrom} was snapshotted
     * — those are newer than the merge and are kept in front of it. Returns once the swap lands;
     * the caller is responsible for calling {@code retire()} on each table in {@code mergedFrom}.
     */
    public void publishCompacted(List<SSTableReader> mergedFrom, SSTableReader merged) {
        while (true) {
            List<SSTableReader> current = readers.get();
            List<SSTableReader> addedSinceSnapshot = new ArrayList<>();
            for (SSTableReader reader : current) {
                if (!mergedFrom.contains(reader)) {
                    addedSinceSnapshot.add(reader);
                }
            }
            List<SSTableReader> replacement = new ArrayList<>(addedSinceSnapshot.size() + 1);
            replacement.addAll(addedSinceSnapshot);
            replacement.add(merged);
            if (readers.compareAndSet(current, List.copyOf(replacement))) {
                return;
            }
        }
    }

    /** Normal shutdown: releases handles only, does not delete any files. */
    public void closeAll() throws IOException {
        for (SSTableReader reader : readers.get()) {
            reader.close();
        }
    }
}
