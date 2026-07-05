package dev.efeg.javakv.server;

import dev.efeg.javakv.net.KvServer;
import dev.efeg.javakv.net.ServerConfig;
import dev.efeg.javakv.storage.KvEngine;

import java.io.IOException;
import java.nio.file.Path;

public final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) throws IOException {
        ServerConfig defaults = ServerConfig.defaults();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : defaults.port();
        Path dataDir = args.length > 1 ? Path.of(args[1]) : defaults.dataDir();
        long flushThresholdBytes = args.length > 2 ? Long.parseLong(args[2]) : KvEngine.DEFAULT_FLUSH_THRESHOLD_BYTES;
        int compactionTriggerCount = args.length > 3 ? Integer.parseInt(args[3]) : KvEngine.DEFAULT_COMPACTION_TRIGGER_COUNT;
        ServerConfig config = new ServerConfig(port, defaults.threadPoolSize(), dataDir);

        KvEngine engine = new KvEngine(config.dataDir(), flushThresholdBytes, compactionTriggerCount);
        KvServer server = new KvServer(config, engine);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            try {
                engine.close();
            } catch (IOException ignored) {
                // Best-effort on shutdown; the WAL is already fsynced after every write.
            }
        }));
        server.start();
    }
}
