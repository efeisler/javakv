package dev.efeg.javakv.storage.wal;

import dev.efeg.javakv.util.Crc32Util;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
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

    /** Guards against a corrupted length prefix triggering a huge, doomed allocation. */
    private static final int MAX_FIELD_LENGTH = 64 * 1024 * 1024;

    private WalReader() {
    }

    public static List<WalRecord> readAll(Path file) throws IOException {
        List<WalRecord> records = new ArrayList<>();
        if (!Files.exists(file)) {
            return records;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            WalRecord record;
            while ((record = readOne(in)) != null) {
                records.add(record);
            }
        }
        return records;
    }

    private static WalRecord readOne(DataInputStream in) throws IOException {
        ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bodyBuffer);

        int keyLen;
        try {
            keyLen = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (keyLen < 0 || keyLen > MAX_FIELD_LENGTH) {
            return null;
        }
        body.writeInt(keyLen);

        byte[] key = new byte[keyLen];
        if (!readFully(in, key)) {
            return null;
        }
        body.write(key);

        long expiry;
        try {
            expiry = in.readLong();
        } catch (EOFException e) {
            return null;
        }
        body.writeLong(expiry);

        int valueLen;
        try {
            valueLen = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (valueLen < -1 || valueLen > MAX_FIELD_LENGTH) {
            return null;
        }
        body.writeInt(valueLen);

        byte[] value = null;
        if (valueLen >= 0) {
            value = new byte[valueLen];
            if (!readFully(in, value)) {
                return null;
            }
            body.write(value);
        }

        int storedCrc;
        try {
            storedCrc = in.readInt();
        } catch (EOFException e) {
            return null;
        }

        byte[] bodyBytes = bodyBuffer.toByteArray();
        if (Crc32Util.compute(bodyBytes, 0, bodyBytes.length) != storedCrc) {
            return null;
        }

        return new WalRecord(key, value, expiry);
    }

    private static boolean readFully(DataInputStream in, byte[] dest) throws IOException {
        try {
            in.readFully(dest);
            return true;
        } catch (EOFException e) {
            return false;
        }
    }
}
