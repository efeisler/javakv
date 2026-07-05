package dev.efeg.javakv.storage.sstable;

import dev.efeg.javakv.storage.Record;
import dev.efeg.javakv.storage.RecordCodec;
import dev.efeg.javakv.util.Bytes;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Read-only handle onto one immutable SSTable file written by {@link SSTableWriter}. */
public final class SSTableReader implements Closeable {

    private static final int FOOTER_SIZE = 24;

    private final FileChannel channel;
    private final List<IndexEntry> index;
    private final long dataSectionEnd;

    private SSTableReader(FileChannel channel, List<IndexEntry> index, long dataSectionEnd) {
        this.channel = channel;
        this.index = index;
        this.dataSectionEnd = dataSectionEnd;
    }

    public static SSTableReader open(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        long fileSize = channel.size();

        ByteBuffer footer = ByteBuffer.allocate(FOOTER_SIZE);
        readFullyAt(channel, footer, fileSize - FOOTER_SIZE);
        footer.flip();
        long indexOffset = footer.getLong();
        long indexSize = footer.getLong();
        footer.getLong(); // entryCount, currently unused beyond the file format's self-description

        ByteBuffer indexBuf = ByteBuffer.allocate((int) indexSize);
        readFullyAt(channel, indexBuf, indexOffset);
        indexBuf.flip();

        List<IndexEntry> index = new ArrayList<>();
        while (indexBuf.hasRemaining()) {
            int keyLen = indexBuf.getInt();
            byte[] key = new byte[keyLen];
            indexBuf.get(key);
            long dataOffset = indexBuf.getLong();
            index.add(new IndexEntry(key, dataOffset));
        }

        return new SSTableReader(channel, index, indexOffset);
    }

    /** Returns the stored record for {@code key} (which may be a tombstone), or {@code null} if absent. */
    public Record get(byte[] key) throws IOException {
        int floorIdx = floorIndex(key);
        if (floorIdx < 0) {
            return null;
        }

        PositionalInputStream positional = new PositionalInputStream(channel, index.get(floorIdx).dataOffset());
        DataInputStream in = new DataInputStream(positional);
        while (positional.position() < dataSectionEnd) {
            RecordCodec.Decoded decoded = RecordCodec.decode(in);
            if (decoded == null) {
                return null; // an immutable, correctly-written SSTable should never hit this
            }
            int cmp = Bytes.COMPARATOR.compare(decoded.key(), key);
            if (cmp == 0) {
                return decoded.isTombstone() ? Record.tombstone() : toRecord(decoded);
            }
            if (cmp > 0) {
                return null; // sorted data: passed where the key would be, so it isn't present
            }
        }
        return null;
    }

    private int floorIndex(byte[] key) {
        int lo = 0;
        int hi = index.size() - 1;
        int result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = Bytes.COMPARATOR.compare(index.get(mid).key(), key);
            if (cmp <= 0) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    private static Record toRecord(RecordCodec.Decoded decoded) {
        return decoded.expiryEpochMillis() == 0
                ? Record.of(decoded.value())
                : Record.ofWithExpiry(decoded.value(), decoded.expiryEpochMillis());
    }

    private static void readFullyAt(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, pos);
            if (n < 0) {
                throw new EOFException("unexpected end of file at position " + pos);
            }
            pos += n;
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private record IndexEntry(byte[] key, long dataOffset) {
    }

    /**
     * Reads from a fixed starting position using {@link FileChannel#read(ByteBuffer, long)},
     * which does not touch the channel's shared position — safe for concurrent lookups against
     * the same open SSTable file from multiple client-handler threads.
     */
    private static final class PositionalInputStream extends InputStream {
        private final FileChannel channel;
        private long position;

        PositionalInputStream(FileChannel channel, long position) {
            this.channel = channel;
            this.position = position;
        }

        long position() {
            return position;
        }

        @Override
        public int read() throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            int n = channel.read(buffer, position);
            if (n <= 0) {
                return -1;
            }
            position++;
            return buffer.get(0) & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
            int n = channel.read(buffer, position);
            if (n > 0) {
                position += n;
            }
            return n;
        }
    }
}
