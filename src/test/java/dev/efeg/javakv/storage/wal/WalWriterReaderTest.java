package dev.efeg.javakv.storage.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalWriterReaderTest {

    @TempDir
    Path dir;

    @Test
    void writesAndReadsBackRecordsInOrder() throws IOException {
        Path file = dir.resolve("wal.log");
        try (WalWriter writer = new WalWriter(file)) {
            writer.append(new WalRecord(bytes("a"), bytes("1"), 0));
            writer.append(new WalRecord(bytes("b"), bytes("2"), 12345));
            writer.append(new WalRecord(bytes("a"), null, 0));
        }

        List<WalRecord> records = WalReader.readAll(file);

        assertEquals(3, records.size());

        assertArrayEquals(bytes("a"), records.get(0).key());
        assertArrayEquals(bytes("1"), records.get(0).value());
        assertEquals(0, records.get(0).expiryEpochMillis());

        assertArrayEquals(bytes("b"), records.get(1).key());
        assertEquals(12345, records.get(1).expiryEpochMillis());

        assertTrue(records.get(2).isTombstone());
    }

    @Test
    void readingAMissingFileReturnsAnEmptyList() throws IOException {
        List<WalRecord> records = WalReader.readAll(dir.resolve("nope.log"));

        assertTrue(records.isEmpty());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
