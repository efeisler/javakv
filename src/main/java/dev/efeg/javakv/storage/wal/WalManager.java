package dev.efeg.javakv.storage.wal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns the currently-active WAL segment file and rotates to a new one on demand. Segments are
 * named {@code wal-<generation>.log}; a segment must only be deleted once its data has been
 * durably flushed into an SSTable (see {@code KvEngine.flush}) — never before.
 */
public final class WalManager implements Closeable {

    private final Path dataDir;
    private int currentGeneration;
    private WalWriter currentWriter;

    public WalManager(Path dataDir, int startGeneration) throws IOException {
        this.dataDir = dataDir;
        this.currentGeneration = startGeneration;
        this.currentWriter = new WalWriter(segmentPath(dataDir, startGeneration));
    }

    public static Path segmentPath(Path dataDir, int generation) {
        return dataDir.resolve("wal-" + generation + ".log");
    }

    /** Existing segment generations found on disk, ascending (oldest first). */
    public static List<Integer> existingGenerations(Path dataDir) throws IOException {
        List<Integer> generations = new ArrayList<>();
        if (!Files.isDirectory(dataDir)) {
            return generations;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "wal-*.log")) {
            for (Path path : stream) {
                generations.add(parseGeneration(path));
            }
        }
        Collections.sort(generations);
        return generations;
    }

    private static int parseGeneration(Path path) {
        String name = path.getFileName().toString();
        String digits = name.substring("wal-".length(), name.length() - ".log".length());
        return Integer.parseInt(digits);
    }

    public void append(WalRecord record) throws IOException {
        currentWriter.append(record);
    }

    /** Seals the current segment and starts a new one. Returns the sealed segment's generation. */
    public int rotate() throws IOException {
        currentWriter.close();
        int sealedGeneration = currentGeneration;
        currentGeneration++;
        currentWriter = new WalWriter(segmentPath(dataDir, currentGeneration));
        return sealedGeneration;
    }

    public void deleteSegment(int generation) throws IOException {
        Files.deleteIfExists(segmentPath(dataDir, generation));
    }

    @Override
    public void close() throws IOException {
        currentWriter.close();
    }
}
