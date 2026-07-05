package dev.efeg.javakv.storage.sstable;

import dev.efeg.javakv.storage.Record;
import dev.efeg.javakv.storage.RecordCodec;
import dev.efeg.javakv.util.FileUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes an already-sorted sequence of entries to an immutable SSTable file:
 *
 * <pre>
 *   data section  : sequence of RecordCodec-encoded records, sorted by key ascending
 *   index section : sparse, one entry per INDEX_INTERVAL records (key, dataOffset),
 *                   the very first record is always indexed so a floor search never under-shoots
 *   footer        : indexOffset(int64) + indexSize(int64) + entryCount(int64), 24 bytes fixed
 * </pre>
 *
 * Written to a {@code .sst.tmp} sibling, fsynced, then atomically renamed onto the target —
 * a reader can never observe a partially-written file.
 */
public final class SSTableWriter {

    public static final int INDEX_INTERVAL = 32;
    private static final int FOOTER_SIZE = 24;

    private SSTableWriter() {
    }

    public static void write(Path targetFile, Iterable<Map.Entry<byte[], Record>> sortedEntries) throws IOException {
        Path tmp = targetFile.resolveSibling(targetFile.getFileName().toString() + ".tmp");
        List<byte[]> indexKeys = new ArrayList<>();
        List<Long> indexOffsets = new ArrayList<>();
        long entryCount = 0;
        long offset = 0;

        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int sinceLastIndex = INDEX_INTERVAL;
            for (Map.Entry<byte[], Record> entry : sortedEntries) {
                byte[] key = entry.getKey();
                Record record = entry.getValue();
                if (sinceLastIndex >= INDEX_INTERVAL) {
                    indexKeys.add(key);
                    indexOffsets.add(offset);
                    sinceLastIndex = 0;
                }

                byte[] encoded = RecordCodec.encode(key, record.isTombstone() ? null : record.value(), record.expiryEpochMillis());
                FileUtil.writeFully(channel, ByteBuffer.wrap(encoded));
                offset += encoded.length;
                sinceLastIndex++;
                entryCount++;
            }

            long indexOffset = offset;
            for (int i = 0; i < indexKeys.size(); i++) {
                byte[] indexEntry = encodeIndexEntry(indexKeys.get(i), indexOffsets.get(i));
                FileUtil.writeFully(channel, ByteBuffer.wrap(indexEntry));
                offset += indexEntry.length;
            }
            long indexSize = offset - indexOffset;

            ByteBuffer footer = ByteBuffer.allocate(FOOTER_SIZE);
            footer.putLong(indexOffset);
            footer.putLong(indexSize);
            footer.putLong(entryCount);
            footer.flip();
            FileUtil.writeFully(channel, footer);

            channel.force(true);
        }

        FileUtil.moveAtomically(tmp, targetFile);
    }

    private static byte[] encodeIndexEntry(byte[] key, long dataOffset) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(key.length);
        out.write(key);
        out.writeLong(dataOffset);
        return buffer.toByteArray();
    }
}
