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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactorTombstoneDropTest {

    @TempDir
    Path dir;

    @Test
    void aTombstoneInTheNewestSourceRemovesTheKeyEntirelyFromTheMergedOutput() throws IOException {
        TreeMap<byte[], Record> oldestData = new TreeMap<>(Bytes.COMPARATOR);
        oldestData.put(bytes("k"), Record.of(bytes("v1")));
        oldestData.put(bytes("keep"), Record.of(bytes("still-here")));
        Path oldestFile = dir.resolve("sstable-0.sst");
        SSTableWriter.write(oldestFile, oldestData.entrySet());

        TreeMap<byte[], Record> newestData = new TreeMap<>(Bytes.COMPARATOR);
        newestData.put(bytes("k"), Record.tombstone());
        Path newestFile = dir.resolve("sstable-1.sst");
        SSTableWriter.write(newestFile, newestData.entrySet());

        SSTableSet sstables = new SSTableSet();
        sstables.addNewest(SSTableReader.open(oldestFile));
        sstables.addNewest(SSTableReader.open(newestFile));

        Compactor compactor = new Compactor(dir, sstables, new AtomicInteger(2));
        assertTrue(compactor.compactIfNeeded(2));

        List<SSTableReader> after = sstables.snapshot();
        SSTableReader merged = after.get(0);
        assertNull(merged.get(bytes("k")), "tombstoned key must not appear at all in the merged table");
        assertNull(merged.get(bytes("k")), "double-checking absence, not just a null value on a present tombstone");
        assertTrue(merged.get(bytes("keep")) != null, "unrelated key must survive the merge");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
