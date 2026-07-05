package dev.efeg.javakv.storage.wal;

import dev.efeg.javakv.util.Crc32Util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends {@link WalRecord}s to a file, fsyncing after every append. On-disk layout, shared
 * with the future SSTable data section:
 *
 * <pre>
 *   keyLen   : int32
 *   key      : keyLen bytes
 *   expiry   : int64   (0 = no TTL)
 *   valueLen : int32   (-1 = tombstone, otherwise the value length)
 *   value    : valueLen bytes (absent for a tombstone)
 *   crc32    : int32   (checksum over everything above)
 * </pre>
 */
public final class WalWriter implements Closeable {

    private final FileChannel channel;

    public WalWriter(Path file) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    public void append(WalRecord record) throws IOException {
        byte[] body = encodeBody(record);
        int crc = Crc32Util.compute(body, 0, body.length);

        ByteBuffer crcBuffer = ByteBuffer.allocate(4);
        crcBuffer.putInt(crc);
        crcBuffer.flip();

        channel.write(new ByteBuffer[] {ByteBuffer.wrap(body), crcBuffer});
        channel.force(false);
    }

    private static byte[] encodeBody(WalRecord record) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(buffer);

        data.writeInt(record.key().length);
        data.write(record.key());
        data.writeLong(record.expiryEpochMillis());

        byte[] value = record.value();
        if (value == null) {
            data.writeInt(-1);
        } else {
            data.writeInt(value.length);
            data.write(value);
        }

        return buffer.toByteArray();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
