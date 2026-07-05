package dev.efeg.javakv.client;

import dev.efeg.javakv.protocol.LineReader;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** A single request/response round trip against a running javakv server. */
public final class KvClientConnection implements Closeable {

    private final Socket socket;
    private final BufferedInputStream in;
    private final OutputStream out;

    public KvClientConnection(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Sends one command line and returns the server's response line (without its terminator). */
    public String sendLine(String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return LineReader.readLine(in);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
