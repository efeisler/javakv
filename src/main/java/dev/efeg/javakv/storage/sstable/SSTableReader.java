package dev.efeg.javakv.storage.sstable;

import dev.efeg.javakv.storage.Record;
import dev.efeg.javakv.storage.RecordCodec;
import dev.efeg.javakv.util.Bytes;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Read-only handle onto one immutable SSTable file written by {@link SSTableWriter}.
 *
 * <p>Reference-counted so the compactor can safely delete a superseded file: the file is only
 * closed and physically deleted once every in-flight {@link #get} caller has released it — this
 * matters especially on Windows, which refuses to delete a file that still has an open handle.
 * Normal shutdown ({@link #close}) is a separate, simpler path that just releases the handle
 * without deleting the file, since the table is still needed on the next restart.
 */
public final class SSTableReader implements Closeable {

    private static final int FOOTER_SIZE = 24;

    private final Path file;
    private final FileChannel channel;
    private final List<IndexEntry> index;
    private final long dataSectionEnd;
    private final AtomicInteger refCount = new AtomicInteger(1);
    private volatile boolean retired;

    private SSTableReader(Path file, FileChannel channel, List<IndexEntry> index, long dataSectionEnd) {
        this.file = file;
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

        return new SSTableReader(file, channel, index, indexOffset);
    }

    /** Tries to record a new user of this table; fails if it has already been fully retired. */
    public boolean tryAcquire() {
        while (true) {
            int current = refCount.get();
            if (current <= 0) {
                return false;
            }
            if (refCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Releases a reference obtained via {@link #tryAcquire}; deletes the file if this was the last one. */
    public void release() {
        if (refCount.decrementAndGet() == 0 && retired) {
            closeAndDelete();
        }
    }

    /** Called by the compactor once this table has been superseded and removed from the live set. */
    public void retire() {
        retired = true;
        release(); // releases the implicit reference this reader was created with
    }

    private void closeAndDelete() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best-effort; nothing more to do if the handle won't close cleanly.
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best-effort: a leftover file (e.g. antivirus briefly holding a handle on Windows) is
            // harmless — the next compaction pass will fold it in again and retry the deletion.
        }
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

    /** Streams every entry in ascending key order, as written — used by the compactor's merge. */
    public Iterator<Map.Entry<byte[], Record>> entryIterator() {
        return new EntryIterator();
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

    /** Normal shutdown: release the handle only, keep the file on disk for the next restart. */
    @Override
    public void close() throws IOException {
        channel.close();
    }

    private record IndexEntry(byte[] key, long dataOffset) {
    }

    private final class EntryIterator implements Iterator<Map.Entry<byte[], Record>> {
        private final PositionalInputStream positional = new PositionalInputStream(channel, 0);
        private final DataInputStream in = new DataInputStream(positional);
        private Map.Entry<byte[], Record> next;

        EntryIterator() {
            advance();
        }

        private void advance() {
            if (positional.position() >= dataSectionEnd) {
                next = null;
                return;
            }
            try {
                RecordCodec.Decoded decoded = RecordCodec.decode(in);
                next = decoded == null ? null : Map.entry(decoded.key(), decoded.isTombstone() ? Record.tombstone() : toRecord(decoded));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Map.Entry<byte[], Record> next() {
            if (next == null) {
                throw new NoSuchElementException();
            }
            Map.Entry<byte[], Record> current = next;
            advance();
            return current;
        }
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
