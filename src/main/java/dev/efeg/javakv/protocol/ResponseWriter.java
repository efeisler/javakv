package dev.efeg.javakv.protocol;

import java.nio.charset.StandardCharsets;

/** Builds wire-format response lines. Every response is a single CRLF-terminated line. */
public final class ResponseWriter {

    private ResponseWriter() {
    }

    public static String ok() {
        return "+OK\r\n";
    }

    public static String pong() {
        return "+PONG\r\n";
    }

    public static String bulk(byte[] value) {
        return "$" + new String(value, StandardCharsets.UTF_8) + "\r\n";
    }

    public static String nilBulk() {
        return "$-1\r\n";
    }

    public static String integer(long n) {
        return ":" + n + "\r\n";
    }

    public static String error(String message) {
        return "-ERR " + message + "\r\n";
    }
}
