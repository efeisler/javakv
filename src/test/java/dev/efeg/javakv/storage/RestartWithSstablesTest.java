package dev.efeg.javakv.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RestartWithSstablesTest {

    @TempDir
    Path dataDir;

    @Test
    void allKeysResolveCorrectlyAfterARestartWithFlushedSstablesOnDisk() throws IOException {
        KvEngine first = new KvEngine(dataDir, 200); // small threshold forces several flushes
        int total = 40;
        for (int i = 0; i < total; i++) {
            first.put(key(i), value(i), 0);
        }
        first.delete(key(3));
        first.close();

        KvEngine restarted = new KvEngine(dataDir, 200);
        for (int i = 0; i < total; i++) {
            if (i == 3) {
                assertNull(restarted.get(key(i)));
            } else {
                assertArrayEquals(value(i), restarted.get(key(i)));
            }
        }
        restarted.close();
    }

    private static byte[] key(int i) {
        return ("key-" + String.format("%05d", i)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i) {
        return ("value-" + i).getBytes(StandardCharsets.UTF_8);
    }
}
