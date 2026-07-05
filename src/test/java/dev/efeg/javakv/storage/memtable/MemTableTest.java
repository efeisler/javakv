package dev.efeg.javakv.storage.memtable;

import dev.efeg.javakv.storage.Record;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemTableTest {

    @Test
    void putThenGetReturnsStoredValue() {
        MemTable memTable = new MemTable();
        memTable.put(bytes("a"), Record.of(bytes("1")));

        assertArrayEquals(bytes("1"), memTable.get(bytes("a")).value());
    }

    @Test
    void missingKeyReturnsNull() {
        MemTable memTable = new MemTable();

        assertNull(memTable.get(bytes("missing")));
    }

    @Test
    void tombstoneOverwritesPreviousValue() {
        MemTable memTable = new MemTable();
        memTable.put(bytes("a"), Record.of(bytes("1")));
        memTable.put(bytes("a"), Record.tombstone());

        assertTrue(memTable.get(bytes("a")).isTombstone());
    }

    @Test
    void approxSizeBytesShrinksWhenAKeyIsOverwrittenWithASmallerValue() {
        MemTable memTable = new MemTable();
        memTable.put(bytes("a"), Record.of(bytes("12345")));
        long afterFirstWrite = memTable.approxSizeBytes();

        memTable.put(bytes("a"), Record.of(bytes("1")));

        assertTrue(memTable.approxSizeBytes() < afterFirstWrite);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
