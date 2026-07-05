package dev.efeg.javakv.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandParserTest {

    @Test
    void parsesPing() {
        assertInstanceOf(PingCommand.class, CommandParser.parse("PING"));
    }

    @Test
    void verbIsCaseInsensitive() {
        assertInstanceOf(PingCommand.class, CommandParser.parse("ping"));
    }

    @Test
    void parsesGet() {
        GetCommand command = (GetCommand) CommandParser.parse("GET foo");

        assertEquals("foo", str(command.key()));
    }

    @Test
    void parsesDel() {
        DelCommand command = (DelCommand) CommandParser.parse("DEL foo");

        assertEquals("foo", str(command.key()));
    }

    @Test
    void parsesSetWithMultiWordValue() {
        SetCommand command = (SetCommand) CommandParser.parse("SET foo bar baz");

        assertEquals("foo", str(command.key()));
        assertEquals("bar baz", str(command.value()));
        assertEquals(0, command.expirySeconds());
    }

    @Test
    void parsesSetWithTrailingExpiry() {
        SetCommand command = (SetCommand) CommandParser.parse("SET foo bar baz EX 30");

        assertEquals("foo", str(command.key()));
        assertEquals("bar baz", str(command.value()));
        assertEquals(30, command.expirySeconds());
    }

    @Test
    void throwsOnUnknownCommand() {
        assertThrows(ProtocolException.class, () -> CommandParser.parse("FOO bar"));
    }

    @Test
    void throwsOnGetWithoutKey() {
        assertThrows(ProtocolException.class, () -> CommandParser.parse("GET"));
    }

    @Test
    void throwsOnGetWithExtraArguments() {
        assertThrows(ProtocolException.class, () -> CommandParser.parse("GET foo bar"));
    }

    @Test
    void throwsOnSetWithoutValue() {
        assertThrows(ProtocolException.class, () -> CommandParser.parse("SET foo"));
    }

    private static String str(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
