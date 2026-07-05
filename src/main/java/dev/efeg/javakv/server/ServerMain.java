package dev.efeg.javakv.server;

import dev.efeg.javakv.net.KvServer;
import dev.efeg.javakv.net.ServerConfig;
import dev.efeg.javakv.storage.KvEngine;

import java.io.IOException;

public final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : ServerConfig.defaults().port();
        ServerConfig config = new ServerConfig(port, ServerConfig.defaults().threadPoolSize());
        KvEngine engine = new KvEngine();
        KvServer server = new KvServer(config, engine);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
    }
}
