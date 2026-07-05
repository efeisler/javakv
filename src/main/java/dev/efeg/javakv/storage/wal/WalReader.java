package dev.efeg.javakv.storage.wal;

import dev.efeg.javakv.storage.RecordCodec;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays a WAL file written by {@link WalWriter}. Stops at the first truncated or corrupt
 * record instead of throwing: an incomplete final record is the normal signature of a crash
 * mid-append, not an error condition.
 */
public final class WalReader {

    private WalReader() {
    }

    public static List<WalRecord> readAll(Path file) throws IOException {
        List<WalRecord> records = new ArrayList<>();
        if (!Files.exists(file)) {
            return records;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            RecordCodec.Decoded decoded;
            while ((decoded = RecordCodec.decode(in)) != null) {
                records.add(new WalRecord(decoded.key(), decoded.value(), decoded.expiryEpochMillis()));
            }
        }
        return records;
    }
}
