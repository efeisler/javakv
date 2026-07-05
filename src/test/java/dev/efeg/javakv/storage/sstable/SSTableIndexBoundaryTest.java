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
import static org.junit.jupiter.api.Assertions.assertNull;

class SSTableIndexBoundaryTest {

    @TempDir
    Path dir;

    @Test
    void findsKeysAcrossMultipleIndexIntervalsIncludingFirstAndLast() throws IOException {
        TreeMap<byte[], Record> data = new TreeMap<>(Bytes.COMPARATOR);
        int total = SSTableWriter.INDEX_INTERVAL * 3 + 5; // spans several intervals, not aligned
        for (int i = 0; i < total; i++) {
            data.put(key(i), Record.of(value(i)));
        }

        Path file = dir.resolve("sstable-0.sst");
        SSTableWriter.write(file, data.entrySet());

        try (SSTableReader reader = SSTableReader.open(file)) {
            assertArrayEquals(value(0), reader.get(key(0)).value());
            assertArrayEquals(value(total - 1), reader.get(key(total - 1)).value());

            int straddling = SSTableWriter.INDEX_INTERVAL + SSTableWriter.INDEX_INTERVAL / 2;
            assertArrayEquals(value(straddling), reader.get(key(straddling)).value());

            assertNull(reader.get(bytes("zzz-not-present")));
        }
    }

    private static byte[] key(int i) {
        return bytes(String.format("key-%05d", i));
    }

    private static byte[] value(int i) {
        return bytes("value-" + i);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
