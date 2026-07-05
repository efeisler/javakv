package dev.efeg.javakv.storage.memtable;

import dev.efeg.javakv.storage.Record;
import dev.efeg.javakv.util.Bytes;

import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory sorted table of the most recent writes, backed by a lock-free skip list. */
public final class MemTable {

    private final ConcurrentNavigableMap<byte[], Record> entries = new ConcurrentSkipListMap<>(Bytes.COMPARATOR);
    private final AtomicLong approxSizeBytes = new AtomicLong();

    public void put(byte[] key, Record record) {
        Record previous = entries.put(key, record);
        long delta = entrySize(key, record) - (previous == null ? 0 : entrySize(key, previous));
        approxSizeBytes.addAndGet(delta);
    }

    public Record get(byte[] key) {
        return entries.get(key);
    }

    public int size() {
        return entries.size();
    }

    public long approxSizeBytes() {
        return approxSizeBytes.get();
    }

    private static long entrySize(byte[] key, Record record) {
        return key.length + (record.isTombstone() ? 0 : record.value().length);
    }
}
