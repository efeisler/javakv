package dev.efeg.javakv.storage;

import dev.efeg.javakv.storage.compaction.Compactor;
import dev.efeg.javakv.storage.memtable.MemTable;
import dev.efeg.javakv.storage.sstable.SSTableReader;
import dev.efeg.javakv.storage.sstable.SSTableWriter;
import dev.efeg.javakv.storage.wal.WalManager;
import dev.efeg.javakv.storage.wal.WalReader;
import dev.efeg.javakv.storage.wal.WalRecord;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Facade the network layer talks to. Every mutation is appended to the write-ahead log before
 * it is applied to the active MemTable, under a single writer lock. When the active MemTable
 * grows past {@link #flushThresholdBytes}, it is frozen and flushed to a new immutable SSTable;
 * the flush's disk I/O runs outside the writer lock so concurrent puts/deletes aren't blocked by
 * it (only a single flush is ever in flight at a time — see {@link #maybeTriggerFlush}).
 */
public final class KvEngine implements Closeable {

    public static final long DEFAULT_FLUSH_THRESHOLD_BYTES = 4L * 1024 * 1024;
    public static final int DEFAULT_COMPACTION_TRIGGER_COUNT = 4;

    private final Path dataDir;
    private final long flushThresholdBytes;
    private final int compactionTriggerCount;
    private final AtomicReference<MemTable> active = new AtomicReference<>(new MemTable());
    private final SSTableSet sstables = new SSTableSet();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicInteger nextSstableGeneration = new AtomicInteger();
    private final WalManager walManager;
    private final Compactor compactor;
    private final Clock clock;

    // Non-null only while a flush is in flight. Reads must be checked in this exact order
    // (active -> frozen -> sstables): the flush() method publishes the new SSTable into
    // `sstables` *before* clearing this field, and both are plain volatile-backed fields rather
    // than one combined snapshot object. A reader that observes `frozen == null` therefore also
    // sees `sstables` already containing the corresponding table, by the Java Memory Model's
    // volatile happens-before rule — no torn read is possible as long as this check order holds.
    private volatile MemTable frozen;

    public KvEngine(Path dataDir) throws IOException {
        this(dataDir, DEFAULT_FLUSH_THRESHOLD_BYTES);
    }

    public KvEngine(Path dataDir, long flushThresholdBytes) throws IOException {
        this(dataDir, flushThresholdBytes, DEFAULT_COMPACTION_TRIGGER_COUNT);
    }

    public KvEngine(Path dataDir, long flushThresholdBytes, int compactionTriggerCount) throws IOException {
        this(dataDir, flushThresholdBytes, compactionTriggerCount, Clock.systemUTC());
    }

    /** Test-only entry point: injecting a {@link Clock} makes TTL expiry deterministic to test. */
    public KvEngine(Path dataDir, long flushThresholdBytes, int compactionTriggerCount, Clock clock) throws IOException {
        this.dataDir = dataDir;
        this.flushThresholdBytes = flushThresholdBytes;
        this.compactionTriggerCount = compactionTriggerCount;
        this.clock = clock;
        Files.createDirectories(dataDir);

        deleteOrphanedTempFiles();
        loadExistingSstables();
        this.compactor = new Compactor(dataDir, sstables, nextSstableGeneration);

        List<Integer> walGenerations = WalManager.existingGenerations(dataDir);
        for (int generation : walGenerations) {
            for (WalRecord record : WalReader.readAll(WalManager.segmentPath(dataDir, generation))) {
                applyToMemTable(active.get(), record);
            }
        }
        int freshWalGeneration = walGenerations.isEmpty() ? 0 : walGenerations.get(walGenerations.size() - 1) + 1;
        this.walManager = new WalManager(dataDir, freshWalGeneration);
        // Consolidate: re-persist the replayed state under the fresh segment before dropping the
        // old ones, so at most one WAL segment exists on disk once recovery finishes. Safe even
        // if this crashes midway: the next restart just replays old-then-new and re-derives the
        // same final state (applying the same values again is idempotent).
        for (Map.Entry<byte[], Record> entry : active.get().entrySet()) {
            walManager.append(toWalRecord(entry.getKey(), entry.getValue()));
        }
        for (int generation : walGenerations) {
            Files.deleteIfExists(WalManager.segmentPath(dataDir, generation));
        }
    }

    /**
     * Removes {@code *.sst.tmp} files left behind by a flush or compaction that crashed before
     * its atomic rename completed. Safe to delete unconditionally: a reader is only ever handed
     * the final {@code .sst} file after the rename succeeds, so a leftover {@code .tmp} was never
     * visible to anything and never contributed data that needs to be preserved.
     */
    private void deleteOrphanedTempFiles() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.sst.tmp")) {
            for (Path path : stream) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void loadExistingSstables() throws IOException {
        List<Integer> generations = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "sstable-*.sst")) {
            for (Path path : stream) {
                generations.add(parseSstableGeneration(path));
            }
        }
        Collections.sort(generations); // ascending: addNewest's prepend then yields newest-first
        int maxGeneration = -1;
        for (int generation : generations) {
            sstables.addNewest(SSTableReader.open(sstableFile(generation)));
            maxGeneration = generation;
        }
        nextSstableGeneration.set(maxGeneration + 1);
    }

    private Path sstableFile(int generation) {
        return dataDir.resolve("sstable-" + generation + ".sst");
    }

    private static int parseSstableGeneration(Path path) {
        String name = path.getFileName().toString(); // sstable-<N>.sst
        String digits = name.substring("sstable-".length(), name.length() - ".sst".length());
        return Integer.parseInt(digits);
    }

    private static void applyToMemTable(MemTable memTable, WalRecord record) {
        memTable.put(record.key(), record.isTombstone() ? Record.tombstone() : toRecord(record));
    }

    private static Record toRecord(WalRecord walRecord) {
        return walRecord.expiryEpochMillis() == 0
                ? Record.of(walRecord.value())
                : Record.ofWithExpiry(walRecord.value(), walRecord.expiryEpochMillis());
    }

    private static WalRecord toWalRecord(byte[] key, Record record) {
        return new WalRecord(key, record.isTombstone() ? null : record.value(), record.expiryEpochMillis());
    }

    public byte[] get(byte[] key) throws IOException {
        Record record = lookup(key);
        return isLive(record) ? record.value() : null;
    }

    /** A tombstone or an expired TTL both mean "no value here" — lazily evaluated at read time. */
    private boolean isLive(Record record) {
        return record != null && !record.isTombstone() && !record.isExpired(clock.millis());
    }

    /** Checks active -> frozen -> sstables, in that order, and stops at the first hit. */
    private Record lookup(byte[] key) throws IOException {
        Record record = active.get().get(key);
        if (record == null) {
            MemTable frozenSnapshot = frozen;
            if (frozenSnapshot != null) {
                record = frozenSnapshot.get(key);
            }
        }
        if (record == null) {
            record = sstables.get(key);
        }
        return record;
    }

    public void put(byte[] key, byte[] value, long expiryEpochMillis) throws IOException {
        FlushJob job;
        writeLock.lock();
        try {
            walManager.append(new WalRecord(key, value, expiryEpochMillis));
            Record record = expiryEpochMillis == 0 ? Record.of(value) : Record.ofWithExpiry(value, expiryEpochMillis);
            active.get().put(key, record);
            job = maybeTriggerFlush();
        } finally {
            writeLock.unlock();
        }
        if (job != null) {
            flush(job);
        }
    }

    public boolean delete(byte[] key) throws IOException {
        FlushJob job;
        boolean existed;
        writeLock.lock();
        try {
            existed = isLive(lookup(key));
            walManager.append(new WalRecord(key, null, 0));
            active.get().put(key, Record.tombstone());
            job = maybeTriggerFlush();
        } finally {
            writeLock.unlock();
        }
        if (job != null) {
            flush(job);
        }
        return existed;
    }

    /** Must be called while holding {@code writeLock}. */
    private FlushJob maybeTriggerFlush() throws IOException {
        if (frozen != null) {
            return null; // a flush is already in flight; at most one at a time
        }
        MemTable currentActive = active.get();
        if (currentActive.approxSizeBytes() < flushThresholdBytes) {
            return null;
        }
        frozen = currentActive;
        active.set(new MemTable());
        int sealedWalGeneration = walManager.rotate();
        return new FlushJob(frozen, sealedWalGeneration);
    }

    /** Runs outside {@code writeLock} so concurrent puts/deletes into the new active table proceed. */
    private void flush(FlushJob job) throws IOException {
        int generation = nextSstableGeneration.getAndIncrement();
        Path target = sstableFile(generation);
        SSTableWriter.write(target, job.memTable().entrySet());
        sstables.addNewest(SSTableReader.open(target));

        writeLock.lock();
        try {
            frozen = null;
        } finally {
            writeLock.unlock();
        }
        walManager.deleteSegment(job.sealedWalGeneration());

        compactor.compactIfNeeded(compactionTriggerCount);
    }

    @Override
    public void close() throws IOException {
        walManager.close();
        sstables.closeAll();
    }

    private record FlushJob(MemTable memTable, int sealedWalGeneration) {
    }
}
