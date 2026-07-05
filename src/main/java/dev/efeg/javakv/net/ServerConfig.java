package dev.efeg.javakv.net;

public record ServerConfig(int port, int threadPoolSize) {

    public static ServerConfig defaults() {
        return new ServerConfig(7379, 64);
    }
}
