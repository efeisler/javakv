package dev.efeg.javakv.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads one CRLF- or LF-terminated line from a socket stream, shared by both the server's
 * {@code ClientHandler} and the CLI client's {@code KvClientConnection}. Bounds the line length
 * so a client that never sends a newline cannot force unbounded buffering.
 */
public final class LineReader {

    public static final int MAX_LINE_LENGTH = 1024 * 1024;

    private LineReader() {
    }

    /** Returns the next line without its line terminator, or {@code null} at end of stream. */
    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') {
                byte[] bytes = buffer.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            buffer.write(b);
            if (buffer.size() > MAX_LINE_LENGTH) {
                throw new IOException("line exceeds maximum length of " + MAX_LINE_LENGTH + " bytes");
            }
        }
        return any ? buffer.toString(StandardCharsets.UTF_8) : null;
    }
}
