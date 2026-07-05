package dev.efeg.javakv.storage;

import dev.efeg.javakv.util.Crc32Util;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Encodes and decodes the single record format shared by the WAL and the SSTable data section:
 *
 * <pre>
 *   keyLen   : int32
 *   key      : keyLen bytes
 *   expiry   : int64   (0 = no TTL)
 *   valueLen : int32   (-1 = tombstone, otherwise the value length)
 *   value    : valueLen bytes (absent for a tombstone)
 *   crc32    : int32   (checksum over everything above)
 * </pre>
 *
 * {@link #decode} returns {@code null} at a clean end-of-stream or the first truncated/corrupt
 * record — the normal signature of a crash mid-write, not an error condition for the caller.
 */
public final class RecordCodec {

    /** Guards against a corrupted length prefix triggering a huge, doomed allocation. */
    private static final int MAX_FIELD_LENGTH = 64 * 1024 * 1024;

    private RecordCodec() {
    }

    public static byte[] encode(byte[] key, byte[] value, long expiryEpochMillis) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(buffer);
        data.writeInt(key.length);
        data.write(key);
        data.writeLong(expiryEpochMillis);
        if (value == null) {
            data.writeInt(-1);
        } else {
            data.writeInt(value.length);
            data.write(value);
        }
        byte[] body = buffer.toByteArray();
        int crc = Crc32Util.compute(body, 0, body.length);

        ByteArrayOutputStream framed = new ByteArrayOutputStream(body.length + 4);
        framed.write(body);
        new DataOutputStream(framed).writeInt(crc);
        return framed.toByteArray();
    }

    public static Decoded decode(DataInputStream in) throws IOException {
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

        return new Decoded(key, value, expiry);
    }

    private static boolean readFully(DataInputStream in, byte[] dest) throws IOException {
        try {
            in.readFully(dest);
            return true;
        } catch (EOFException e) {
            return false;
        }
    }

    public record Decoded(byte[] key, byte[] value, long expiryEpochMillis) {
        public boolean isTombstone() {
            return value == null;
        }
    }
}
