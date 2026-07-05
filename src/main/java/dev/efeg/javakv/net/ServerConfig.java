package dev.efeg.javakv.net;

import java.nio.file.Path;

public record ServerConfig(int port, int threadPoolSize, Path dataDir) {

    public static ServerConfig defaults() {
        return new ServerConfig(7379, 64, Path.of("data"));
    }
}
