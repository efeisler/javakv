package dev.efeg.javakv.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KvEngineReadOrderTest {

    @TempDir
    Path dataDir;

    @Test
    void newestWriteWinsAcrossMemtableAndFlushedSstables() throws IOException {
        KvEngine engine = new KvEngine(dataDir, 50); // small threshold: a few writes trigger a flush

        engine.put(bytes("k"), bytes("v1"), 0);
        // Pad with unrelated keys to push past the flush threshold and force "k" -> v1 to disk.
        for (int i = 0; i < 10; i++) {
            engine.put(("pad-" + i).getBytes(StandardCharsets.UTF_8), bytes("x"), 0);
        }
        engine.put(bytes("k"), bytes("v2"), 0); // newest write, still in the active memtable

        assertArrayEquals(bytes("v2"), engine.get(bytes("k")));

        engine.close();
    }

    @Test
    void deleteReportsExistedTrueForAKeyAlreadyFlushedToAnSstable() throws IOException {
        KvEngine engine = new KvEngine(dataDir, 50);

        engine.put(bytes("k"), bytes("v1"), 0);
        for (int i = 0; i < 10; i++) {
            engine.put(("pad-" + i).getBytes(StandardCharsets.UTF_8), bytes("x"), 0);
        }
        // By now "k" -> v1 has very likely been flushed out of the active MemTable entirely.

        assertTrue(engine.delete(bytes("k")));

        engine.close();
    }

    @Test
    void deleteAfterAFlushHidesTheOlderFlushedValue() throws IOException {
        KvEngine engine = new KvEngine(dataDir, 50);

        engine.put(bytes("k"), bytes("v1"), 0);
        for (int i = 0; i < 10; i++) {
            engine.put(("pad-" + i).getBytes(StandardCharsets.UTF_8), bytes("x"), 0);
        }
        engine.delete(bytes("k"));

        assertNull(engine.get(bytes("k")));

        engine.close();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
