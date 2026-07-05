package dev.efeg.javakv.storage.sstable;

import dev.efeg.javakv.storage.Record;
import dev.efeg.javakv.util.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SSTableReadWriteTest {

    @TempDir
    Path dir;

    @Test
    void writesThenReadsBackEveryKeyIncludingTombstonesAndExpiry() throws IOException {
        TreeMap<byte[], Record> data = new TreeMap<>(Bytes.COMPARATOR);
        data.put(bytes("a"), Record.of(bytes("1")));
        data.put(bytes("b"), Record.tombstone());
        data.put(bytes("c"), Record.ofWithExpiry(bytes("3"), 12345));

        Path file = dir.resolve("sstable-0.sst");
        SSTableWriter.write(file, data.entrySet());

        try (SSTableReader reader = SSTableReader.open(file)) {
            assertArrayEquals(bytes("1"), reader.get(bytes("a")).value());
            assertTrue(reader.get(bytes("b")).isTombstone());
            assertEquals(12345, reader.get(bytes("c")).expiryEpochMillis());
            assertNull(reader.get(bytes("missing")));
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
