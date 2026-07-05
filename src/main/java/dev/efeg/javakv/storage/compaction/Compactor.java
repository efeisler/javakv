package dev.efeg.javakv.storage.compaction;

import dev.efeg.javakv.storage.Record;
import dev.efeg.javakv.storage.SSTableSet;
import dev.efeg.javakv.storage.sstable.SSTableReader;
import dev.efeg.javakv.storage.sstable.SSTableWriter;
import dev.efeg.javakv.util.Bytes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Merges every current SSTable into a single new one via a k-way merge, resolving duplicate keys
 * to the newest source and dropping tombstones. Compaction is always global (all current tables
 * at once) — that is what makes unconditionally dropping tombstones safe: no older copy of a
 * deleted key can survive anywhere else once compaction completes.
 */
public final class Compactor {

    private final Path dataDir;
    private final SSTableSet sstables;
    private final AtomicInteger nextGeneration;

    public Compactor(Path dataDir, SSTableSet sstables, AtomicInteger nextGeneration) {
        this.dataDir = dataDir;
        this.sstables = sstables;
        this.nextGeneration = nextGeneration;
    }

    /** Returns {@code true} if a compaction actually ran. */
    public boolean compactIfNeeded(int triggerCount) throws IOException {
        List<SSTableReader> toMerge = sstables.snapshot();
        if (toMerge.size() < triggerCount) {
            return false;
        }

        List<SSTableReader> acquired = new ArrayList<>();
        try {
            for (SSTableReader reader : toMerge) {
                if (reader.tryAcquire()) {
                    acquired.add(reader);
                }
            }
            if (acquired.size() < 2) {
                return false; // nothing meaningful left to merge; others were retired concurrently
            }

            int generation = nextGeneration.getAndIncrement();
            Path target = dataDir.resolve("sstable-" + generation + ".sst");
            SSTableWriter.write(target, () -> new MergeIterator(acquired));

            SSTableReader merged = SSTableReader.open(target);
            sstables.publishCompacted(toMerge, merged);
        } finally {
            for (SSTableReader reader : acquired) {
                reader.release();
            }
        }
        for (SSTableReader reader : toMerge) {
            reader.retire();
        }
        return true;
    }

    private record Cursor(Iterator<Map.Entry<byte[], Record>> iterator, int sourceRank, Map.Entry<byte[], Record> current) {
    }

    /** Streams the merge of {@code sources} (newest-first) in ascending key order, tombstones dropped. */
    private static final class MergeIterator implements Iterator<Map.Entry<byte[], Record>> {

        private static final Comparator<Cursor> ORDER = Comparator
                .<Cursor, byte[]>comparing(c -> c.current().getKey(), Bytes.COMPARATOR)
                .thenComparingInt(Cursor::sourceRank);

        private final PriorityQueue<Cursor> queue = new PriorityQueue<>(ORDER);
        private Map.Entry<byte[], Record> pending;

        MergeIterator(List<SSTableReader> newestFirstSources) {
            for (int rank = 0; rank < newestFirstSources.size(); rank++) {
                Iterator<Map.Entry<byte[], Record>> it = newestFirstSources.get(rank).entryIterator();
                if (it.hasNext()) {
                    queue.add(new Cursor(it, rank, it.next()));
                }
            }
            advanceToNextLiveEntry();
        }

        private void advanceToNextLiveEntry() {
            pending = null;
            while (!queue.isEmpty()) {
                Cursor head = queue.poll();
                Map.Entry<byte[], Record> winner = head.current();
                requeueIfMore(head);
                while (!queue.isEmpty() && Bytes.COMPARATOR.compare(queue.peek().current().getKey(), winner.getKey()) == 0) {
                    requeueIfMore(queue.poll());
                }
                Record record = winner.getValue();
                if (!record.isTombstone() && !record.isExpired(System.currentTimeMillis())) {
                    pending = winner;
                    return;
                }
                // winner was a tombstone or its TTL has lapsed: safe to drop under global
                // compaction (a bonus cleanup beyond what lazy per-read expiry already does);
                // keep scanning for the next live key
            }
        }

        private void requeueIfMore(Cursor cursor) {
            if (cursor.iterator().hasNext()) {
                queue.add(new Cursor(cursor.iterator(), cursor.sourceRank(), cursor.iterator().next()));
            }
        }

        @Override
        public boolean hasNext() {
            return pending != null;
        }

        @Override
        public Map.Entry<byte[], Record> next() {
            if (pending == null) {
                throw new NoSuchElementException();
            }
            Map.Entry<byte[], Record> result = pending;
            advanceToNextLiveEntry();
            return result;
        }
    }
}
