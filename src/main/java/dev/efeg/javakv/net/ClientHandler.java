package dev.efeg.javakv.net;

import dev.efeg.javakv.protocol.Command;
import dev.efeg.javakv.protocol.CommandParser;
import dev.efeg.javakv.protocol.DelCommand;
import dev.efeg.javakv.protocol.GetCommand;
import dev.efeg.javakv.protocol.LineReader;
import dev.efeg.javakv.protocol.PingCommand;
import dev.efeg.javakv.protocol.ProtocolException;
import dev.efeg.javakv.protocol.QuitCommand;
import dev.efeg.javakv.protocol.ResponseWriter;
import dev.efeg.javakv.protocol.SetCommand;
import dev.efeg.javakv.storage.KvEngine;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Serves one client connection: read a line, parse it, apply it to the engine, write a response. */
final class ClientHandler implements Runnable {

    private final Socket socket;
    private final KvEngine engine;

    ClientHandler(Socket socket, KvEngine engine) {
        this.socket = socket;
        this.engine = engine;
    }

    @Override
    public void run() {
        try (socket;
             BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = socket.getOutputStream()) {
            String line;
            while ((line = LineReader.readLine(in)) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Command command = null;
                String response;
                try {
                    command = CommandParser.parse(line);
                    response = handle(command);
                } catch (ProtocolException e) {
                    response = ResponseWriter.error(e.getMessage());
                }
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (command instanceof QuitCommand) {
                    break;
                }
            }
        } catch (IOException e) {
            // Client disconnected or reset the connection; nothing to clean up beyond try-with-resources.
        }
    }

    private String handle(Command command) {
        return switch (command) {
            case PingCommand c -> ResponseWriter.pong();
            case QuitCommand c -> ResponseWriter.ok();
            case GetCommand c -> {
                byte[] value = engine.get(c.key());
                yield value == null ? ResponseWriter.nilBulk() : ResponseWriter.bulk(value);
            }
            case SetCommand c -> {
                long expiryEpochMillis = c.expirySeconds() > 0
                        ? System.currentTimeMillis() + c.expirySeconds() * 1000
                        : 0;
                engine.put(c.key(), c.value(), expiryEpochMillis);
                yield ResponseWriter.ok();
            }
            case DelCommand c -> ResponseWriter.integer(engine.delete(c.key()) ? 1 : 0);
        };
    }
}
