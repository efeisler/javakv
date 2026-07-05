package dev.efeg.javakv.bench;

import dev.efeg.javakv.client.KvClientConnection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Drives a running javakv server with N concurrent client connections and reports throughput
 * and latency percentiles. Not a microbenchmark of the storage engine in isolation — this
 * exercises the whole stack (TCP, protocol parsing, WAL fsync, MemTable) the way a real client
 * would.
 *
 * <p>Usage: {@code java -cp javakv.jar dev.efeg.javakv.bench.BenchmarkRunner
 * [host] [port] [threads] [opsPerThread] [writePercent]}
 */
public final class BenchmarkRunner {

    private BenchmarkRunner() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 7379;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int opsPerThread = args.length > 3 ? Integer.parseInt(args[3]) : 5000;
        int writePercent = args.length > 4 ? Integer.parseInt(args[4]) : 50;

        System.out.printf(
                "javakv benchmark: host=%s port=%d threads=%d opsPerThread=%d writePercent=%d%n",
                host, port, threads, opsPerThread, writePercent);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<long[]>> futures = new ArrayList<>();
        long startNanos = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> runWorker(host, port, threadId, opsPerThread, writePercent)));
        }

        long[] allLatenciesNanos = new long[threads * opsPerThread];
        int offset = 0;
        for (Future<long[]> future : futures) {
            long[] latencies = future.get();
            System.arraycopy(latencies, 0, allLatenciesNanos, offset, latencies.length);
            offset += latencies.length;
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        pool.shutdown();

        Arrays.sort(allLatenciesNanos);
        int totalOps = allLatenciesNanos.length;
        double seconds = elapsedNanos / 1_000_000_000.0;
        double opsPerSec = totalOps / seconds;

        System.out.printf("total ops: %d in %.2fs -> %.0f ops/sec%n", totalOps, seconds, opsPerSec);
        System.out.printf("latency (ms): p50=%.3f p95=%.3f p99=%.3f max=%.3f%n",
                toMillis(percentile(allLatenciesNanos, 50)),
                toMillis(percentile(allLatenciesNanos, 95)),
                toMillis(percentile(allLatenciesNanos, 99)),
                toMillis(allLatenciesNanos[totalOps - 1]));
    }

    private static long[] runWorker(String host, int port, int threadId, int opsPerThread, int writePercent)
            throws IOException {
        long[] latencies = new long[opsPerThread];
        Random random = new Random(threadId);
        try (KvClientConnection connection = new KvClientConnection(host, port)) {
            for (int i = 0; i < opsPerThread; i++) {
                String key = "bench-" + threadId + "-" + random.nextInt(1000);
                boolean isWrite = random.nextInt(100) < writePercent;

                long start = System.nanoTime();
                if (isWrite) {
                    connection.sendLine("SET " + key + " value-" + i);
                } else {
                    connection.sendLine("GET " + key);
                }
                latencies[i] = System.nanoTime() - start;
            }
        }
        return latencies;
    }

    private static long percentile(long[] sortedNanos, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, Math.min(index, sortedNanos.length - 1))];
    }

    private static double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
