# javakv

A small key-value database server written from scratch in Java, built as a real
LSM-tree storage engine — not a wrapper around a `HashMap`. It has its own
write-ahead log, SSTables with a sparse index, background compaction, a
line-based TCP protocol, and a redis-cli-style client.

## Architecture

```
 client (TCP, line protocol)
        |
        v
   +---------+      writes       +-----------+
   | KvServer|------------------>|  KvEngine |
   +---------+                   +-----------+
                                   |    |    |
                     put/delete    |    |    |  get
              (single writer lock) |    |    |  (lock-free)
                                   v    |    v
                            +-----------+  +------------+
                            | WAL (fsync)| | active      |
                            +-----------+  | MemTable    |
                                           +------------+
                                                 |
                                    threshold exceeded -> flush
                                                 v
                                          +-------------+        SSTable count
                                          | SSTable     | -----> exceeds trigger
                                          | (sorted,    |            |
                                          |  sparse idx)|            v
                                          +-------------+     +-------------+
                                                 ^             | Compactor   |
                                                 +-------------| (k-way merge,
                                                   replaces    |  drops        |
                                                   old tables  |  tombstones)  |
                                                                +-------------+
```

Read path for a `GET`: active MemTable -> frozen MemTable (if a flush is in
flight) -> SSTables, newest to oldest. The search stops at the first hit,
including a tombstone (meaning "deleted") — it never falls through to older,
stale data.

### Durability

Every `SET`/`DEL` is appended to the write-ahead log and `fsync`'d *before* it
is applied to the in-memory table. On startup, any existing WAL segment is
replayed to rebuild the MemTable, so a killed process (`kill -9` / `taskkill
/F`) loses nothing that was acknowledged.

### Flush and compaction

When the active MemTable grows past a size threshold, it is frozen and
written out as a new immutable SSTable file (sorted data + a sparse index +
a footer, written to a `.tmp` file and atomically renamed into place). Once
enough SSTables accumulate, a compactor merges *all* of them into one via a
k-way merge, resolving duplicate keys to the newest write and dropping
tombstones — safe specifically because compaction is always global, so no
older copy of a deleted key can be hiding in another table. SSTable readers
are reference-counted so a file is only deleted once no in-flight read is
using it, which matters in particular on Windows (which refuses to delete a
file with an open handle).

### TTL

`SET key value EX <seconds>` attaches an expiry. Expiry is checked lazily on
read (an expired key reads back as absent, same as a tombstone) and is also
dropped as a bonus during compaction.

## Wire protocol

Line-based and telnet-friendly (CRLF or LF terminated), with Redis-flavored
response tags:

```
SET <key> <value...> [EX <seconds>]   -> +OK
GET <key>                             -> $<value>   or   $-1 (miss)
DEL <key>                             -> :1 (existed) or :0
PING                                  -> +PONG
QUIT                                  -> +OK, then closes the connection
```

Known, deliberate simplification: keys/values can't contain embedded CR/LF at
the protocol layer (the storage engine itself is fully binary-safe on
`byte[]`) — this keeps the protocol typeable directly over telnet.

## Building and running

Requires a JDK (21+). No local Maven install needed — the repo ships its own
Maven Wrapper.

```powershell
# Run the test suite
.\mvnw.cmd test

# Build the runnable jar (target\javakv.jar)
.\mvnw.cmd package

# Start the server: [port] [dataDir] [flushThresholdBytes] [compactionTriggerCount]
java -jar target\javakv.jar 7379 data

# Connect with the bundled CLI client: [host] [port]
java -cp target\javakv.jar dev.efeg.javakv.client.KvCliClient localhost 7379
```

Or, from a second terminal, a plain `telnet localhost 7379` works too, since
the protocol is plain text.

## Benchmark

A small load-testing tool is bundled:

```powershell
# [host] [port] [threads] [opsPerThread] [writePercent]
java -cp target\javakv.jar dev.efeg.javakv.bench.BenchmarkRunner localhost 7379 8 3000 50
```

Measured on the development machine (8 threads, 3000 ops/thread):

| Workload           | Throughput     | p50      | p99       |
|--------------------|---------------:|---------:|----------:|
| 50% SET / 50% GET  | ~1,100 ops/sec | ~3.5 ms  | ~29 ms    |
| 100% GET           | ~65,000 ops/sec| ~0.08 ms | ~0.28 ms  |

The gap is expected, not a bug: every write is `fsync`'d before it's
acknowledged, and all writes go through a single writer lock (see "Known
limitations" below), while reads are entirely lock-free. This is a
durability-first design choice, not an oversight.

## Docker

```
docker build -t javakv .
docker run -p 7379:7379 -v ${PWD}/data:/data javakv
```

Data lives on the mounted volume, not inside the image, so it survives a
container restart as long as the same volume is reused.

## Known limitations / future work

- **Single-writer lock**: every `SET`/`DEL` is serialized through one lock
  (needed for the WAL-append-then-apply and flush-swap invariants). A batched
  / group-commit WAL would let multiple writers share one `fsync` and raise
  write throughput significantly.
- **Global-only compaction**: the compactor always merges every current
  SSTable at once, which is what makes dropping tombstones safe without extra
  bookkeeping. It doesn't scale as gracefully as leveled compaction once the
  data set is much larger than memory.
- **Line protocol**: keys/values can't contain literal CR/LF; a real binary
  protocol (length-prefixed frames) would remove this restriction.
- **One thread per connection**: `KvServer` uses a fixed thread pool sized to
  the configured connection limit, not an async/event-loop model, so a very
  large number of idle connections is comparatively expensive.
