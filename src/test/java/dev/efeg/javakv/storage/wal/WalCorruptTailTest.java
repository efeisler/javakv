package dev.efeg.javakv.storage.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalCorruptTailTest {

    @TempDir
    Path dir;

    @Test
    void stopsCleanlyAtATruncatedFinalRecord() throws IOException {
        Path file = dir.resolve("wal.log");
        try (WalWriter writer = new WalWriter(file)) {
            writer.append(new WalRecord(bytes("a"), bytes("1"), 0));
            writer.append(new WalRecord(bytes("b"), bytes("2"), 0));
        }
        long fullLength = Files.size(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(fullLength - 3);
        }

        List<WalRecord> records = WalReader.readAll(file);

        assertEquals(1, records.size());
        assertArrayEquals(bytes("a"), records.get(0).key());
    }

    @Test
    void stopsCleanlyAtGarbageAppendedAfterAValidRecord() throws IOException {
        Path file = dir.resolve("wal.log");
        try (WalWriter writer = new WalWriter(file)) {
            writer.append(new WalRecord(bytes("a"), bytes("1"), 0));
        }
        Files.write(file, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, StandardOpenOption.APPEND);

        List<WalRecord> records = WalReader.readAll(file);

        assertEquals(1, records.size());
        assertArrayEquals(bytes("a"), records.get(0).key());
    }

    @Test
    void treatsAnAbsurdlyLargeLengthPrefixAsCorruptRatherThanAllocating() throws IOException {
        Path file = dir.resolve("wal.log");
        ByteBuffer huge = ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE);
        Files.write(file, huge.array());

        List<WalRecord> records = WalReader.readAll(file);

        assertTrue(records.isEmpty());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
