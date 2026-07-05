package dev.efeg.javakv.storage.compaction;

import dev.efeg.javakv.storage.Record;
import dev.efeg.javakv.storage.SSTableSet;
import dev.efeg.javakv.storage.sstable.SSTableReader;
import dev.efeg.javakv.storage.sstable.SSTableWriter;
import dev.efeg.javakv.util.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactorConcurrentReadTest {

    @TempDir
    Path dir;

    @Test
    void readsDuringCompactionDoNotThrowAndRemainCorrectAfterward() throws Exception {
        SSTableSet sstables = new SSTableSet();
        int tableCount = 6;
        for (int t = 0; t < tableCount; t++) {
            TreeMap<byte[], Record> data = new TreeMap<>(Bytes.COMPARATOR);
            for (int i = 0; i < 20; i++) {
                data.put(key(t, i), Record.of(value(t, i)));
            }
            Path file = dir.resolve("sstable-" + t + ".sst");
            SSTableWriter.write(file, data.entrySet());
            sstables.addNewest(SSTableReader.open(file));
        }

        Compactor compactor = new Compactor(dir, sstables, new AtomicInteger(tableCount));

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch readerStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        pool.submit(() -> {
            readerStarted.countDown();
            while (!stop.get()) {
                try {
                    for (int t = 0; t < tableCount; t++) {
                        Record record = sstables.get(key(t, 0));
                        if (record == null || record.isTombstone()) {
                            failure.compareAndSet(null, new AssertionError("expected key t=" + t + " to resolve"));
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                    return;
                }
            }
        });

        readerStarted.await();
        boolean ran = compactor.compactIfNeeded(tableCount);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        if (failure.get() != null) {
            throw new AssertionError("background reader failed", failure.get());
        }
        assertTrue(ran);
        assertTrue(sstables.snapshot().size() == 1);

        for (int t = 0; t < tableCount; t++) {
            for (int i = 0; i < 20; i++) {
                Record record = sstables.get(key(t, i));
                assertArrayEquals(value(t, i), record.value());
            }
        }
        assertNull(sstables.get(bytes("definitely-not-present")));
    }

    private static byte[] key(int table, int i) {
        return bytes("t" + table + "-key-" + String.format("%03d", i));
    }

    private static byte[] value(int table, int i) {
        return bytes("t" + table + "-value-" + i);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
