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
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactorMergeTest {

    @TempDir
    Path dir;

    @Test
    void mergesMultipleTablesResolvingDuplicateKeysToTheNewestSource() throws IOException {
        // Oldest table: k -> "old", plus a key nothing else touches.
        SSTableReader oldest = writeTable("sstable-0.sst",
                entry("k", "old"), entry("only-in-oldest", "keepme"));
        // Newest table (written "on top"): k -> "new", superseding the oldest copy.
        SSTableReader newest = writeTable("sstable-1.sst", entry("k", "new"));

        SSTableSet sstables = new SSTableSet();
        // addNewest prepends, so add oldest first, then newest, to get [newest, oldest].
        sstables.addNewest(oldest);
        sstables.addNewest(newest);

        AtomicInteger nextGeneration = new AtomicInteger(2);
        Compactor compactor = new Compactor(dir, sstables, nextGeneration);

        boolean ran = compactor.compactIfNeeded(2);
        assertTrue(ran);

        List<SSTableReader> after = sstables.snapshot();
        assertTrue(after.size() == 1, "expected the two tables to collapse into one");

        assertArrayEquals(bytes("new"), after.get(0).get(bytes("k")).value());
        assertArrayEquals(bytes("keepme"), after.get(0).get(bytes("only-in-oldest")).value());
    }

    private SSTableReader writeTable(String fileName, Entry... entries) throws IOException {
        TreeMap<byte[], Record> data = new TreeMap<>(Bytes.COMPARATOR);
        for (Entry e : entries) {
            data.put(bytes(e.key), Record.of(bytes(e.value)));
        }
        Path file = dir.resolve(fileName);
        SSTableWriter.write(file, data.entrySet());
        return SSTableReader.open(file);
    }

    private static Entry entry(String key, String value) {
        return new Entry(key, value);
    }

    private record Entry(String key, String value) {
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
