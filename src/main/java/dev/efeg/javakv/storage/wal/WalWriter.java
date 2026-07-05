package dev.efeg.javakv.storage.wal;

import dev.efeg.javakv.storage.RecordCodec;
import dev.efeg.javakv.util.FileUtil;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends {@link WalRecord}s to a file, fsyncing after every append. */
public final class WalWriter implements Closeable {

    private final FileChannel channel;

    public WalWriter(Path file) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    public void append(WalRecord record) throws IOException {
        byte[] encoded = RecordCodec.encode(record.key(), record.value(), record.expiryEpochMillis());
        FileUtil.writeFully(channel, ByteBuffer.wrap(encoded));
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
