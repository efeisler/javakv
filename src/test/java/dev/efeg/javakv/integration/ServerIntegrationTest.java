package dev.efeg.javakv.integration;

import dev.efeg.javakv.client.KvClientConnection;
import dev.efeg.javakv.net.KvServer;
import dev.efeg.javakv.net.ServerConfig;
import dev.efeg.javakv.storage.KvEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerIntegrationTest {

    private KvServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void setGetDeletePingRoundTrip() throws IOException {
        server = startServerOnEphemeralPort();

        try (KvClientConnection client = new KvClientConnection("localhost", server.port())) {
            assertEquals("+PONG", client.sendLine("PING"));
            assertEquals("+OK", client.sendLine("SET greeting hello world"));
            assertEquals("$hello world", client.sendLine("GET greeting"));
            assertEquals(":1", client.sendLine("DEL greeting"));
            assertEquals("$-1", client.sendLine("GET greeting"));
            assertEquals(":0", client.sendLine("DEL greeting"));
        }
    }

    @Test
    void secondClientSeesFirstClientsWrites() throws IOException {
        server = startServerOnEphemeralPort();

        try (KvClientConnection writer = new KvClientConnection("localhost", server.port());
             KvClientConnection reader = new KvClientConnection("localhost", server.port())) {
            assertEquals("+OK", writer.sendLine("SET shared value1"));
            assertEquals("$value1", reader.sendLine("GET shared"));
        }
    }

    @Test
    void malformedCommandReturnsErrorButKeepsConnectionOpen() throws IOException {
        server = startServerOnEphemeralPort();

        try (KvClientConnection client = new KvClientConnection("localhost", server.port())) {
            String response = client.sendLine("NOSUCHCOMMAND");
            assertEquals(true, response.startsWith("-ERR"));
            assertEquals("+PONG", client.sendLine("PING"));
        }
    }

    private static KvServer startServerOnEphemeralPort() throws IOException {
        KvServer server = new KvServer(new ServerConfig(0, 8), new KvEngine());
        server.start();
        return server;
    }
}
