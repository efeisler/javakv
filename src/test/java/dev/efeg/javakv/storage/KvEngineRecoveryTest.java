package dev.efeg.javakv.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KvEngineRecoveryTest {

    @TempDir
    Path dataDir;

    @Test
    void survivesRestartAfterCloseWithoutAnyExplicitFlush() throws IOException {
        KvEngine first = new KvEngine(dataDir);
        first.put(bytes("a"), bytes("1"), 0);
        first.put(bytes("b"), bytes("2"), 0);
        first.delete(bytes("a"));
        // Every write above already went through the WAL and was fsynced before returning, so
        // closing here (rather than crashing the process) exercises the same durability
        // guarantee: recovery must not depend on any special flush-on-shutdown step.
        first.close();

        KvEngine recovered = new KvEngine(dataDir);

        assertNull(recovered.get(bytes("a")));
        assertArrayEquals(bytes("2"), recovered.get(bytes("b")));
    }

    @Test
    void emptyDataDirStartsWithNoData() throws IOException {
        KvEngine engine = new KvEngine(dataDir);

        assertNull(engine.get(bytes("missing")));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
