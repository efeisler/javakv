package dev.efeg.javakv.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KvEngineFlushTest {

    @TempDir
    Path dataDir;

    @Test
    void forcesMultipleFlushesAndStillResolvesEveryKey() throws IOException {
        KvEngine engine = new KvEngine(dataDir, 200); // tiny threshold forces several flushes
        int total = 50;
        for (int i = 0; i < total; i++) {
            engine.put(key(i), value(i), 0);
        }

        for (int i = 0; i < total; i++) {
            assertArrayEquals(value(i), engine.get(key(i)));
        }

        assertTrue(countSstableFiles() >= 2, "expected at least 2 flushed SSTables");

        engine.close();
    }

    private long countSstableFiles() throws IOException {
        try (DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(dataDir, "sstable-*.sst")) {
            return StreamSupport.stream(stream.spliterator(), false).count();
        }
    }

    private static byte[] key(int i) {
        return ("key-" + String.format("%05d", i)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i) {
        return ("value-" + i).getBytes(StandardCharsets.UTF_8);
    }
}
