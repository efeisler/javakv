package dev.efeg.javakv.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Interactive redis-cli-style REPL for javakv. */
public final class KvCliClient {

    private KvCliClient() {
    }

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 7379;

        try (KvClientConnection connection = new KvClientConnection(host, port);
             BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("Connected to javakv at " + host + ":" + port
                    + ". Commands: PING, SET <key> <value> [EX <secs>], GET <key>, DEL <key>, QUIT");
            System.out.print("javakv> ");
            String line;
            while ((line = stdin.readLine()) != null) {
                if (!line.isBlank()) {
                    String response = connection.sendLine(line);
                    if (response == null) {
                        System.out.println("(server closed the connection)");
                        break;
                    }
                    System.out.println(response);
                    if (line.strip().equalsIgnoreCase("QUIT")) {
                        break;
                    }
                }
                System.out.print("javakv> ");
            }
        }
    }
}
