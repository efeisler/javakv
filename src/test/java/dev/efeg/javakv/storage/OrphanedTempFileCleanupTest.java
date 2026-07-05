package dev.efeg.javakv.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrphanedTempFileCleanupTest {

    @TempDir
    Path dataDir;

    @Test
    void startupDeletesLeftoverSstTmpFilesWithoutDisturbingRealData() throws IOException {
        KvEngine first = new KvEngine(dataDir);
        first.put(bytes("a"), bytes("1"), 0);
        first.close();

        // Simulate a crash mid-flush/mid-compaction: an orphaned temp file with no matching
        // finished .sst ever having been renamed into place.
        Path orphan = dataDir.resolve("sstable-99.sst.tmp");
        Files.write(orphan, new byte[] {1, 2, 3});
        assertTrue(Files.exists(orphan));

        KvEngine restarted = new KvEngine(dataDir);
        assertArrayEquals(bytes("1"), restarted.get(bytes("a")));
        assertFalse(Files.exists(orphan), "orphaned .sst.tmp should be deleted on startup");
        restarted.close();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
