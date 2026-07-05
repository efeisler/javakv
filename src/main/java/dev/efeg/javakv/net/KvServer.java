package dev.efeg.javakv.net;

import dev.efeg.javakv.storage.KvEngine;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Accepts TCP connections and dispatches each to a {@link ClientHandler} on a pooled thread.
 * {@link #start()} binds the socket synchronously (so {@link #port()} is available immediately
 * after it returns) then runs the accept loop on a dedicated non-daemon thread.
 */
public final class KvServer implements Closeable {

    private final ServerConfig config;
    private final KvEngine engine;
    private final ExecutorService pool;
    private ServerSocket serverSocket;
    private volatile boolean running;

    public KvServer(ServerConfig config, KvEngine engine) {
        this.config = config;
        this.engine = engine;
        this.pool = Executors.newFixedThreadPool(config.threadPoolSize());
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(config.port());
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "javakv-acceptor");
        acceptor.setDaemon(false);
        acceptor.start();
        System.out.println("javakv listening on port " + port());
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                System.err.println("accept failed: " + e.getMessage());
                continue;
            }
            pool.execute(new ClientHandler(client, engine));
        }
    }

    /**
     * Graceful shutdown: stop accepting first, then let in-flight requests drain (bounded, so a
     * stuck client can't hang shutdown forever) before returning. Safe to call from a shutdown
     * hook right before closing the {@link KvEngine} — no handler thread should still be touching
     * it once this returns.
     */
    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Already closed or never opened; nothing more to do.
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
