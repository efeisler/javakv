package dev.efeg.javakv.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses an {@link AdjustableClock} rather than sleeping, so expiry is deterministic to test. */
class TtlExpiryTest {

    @TempDir
    Path dataDir;

    @Test
    void keyIsVisibleBeforeItsTtlAndGoneAfter() throws IOException {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        KvEngine engine = new KvEngine(dataDir, KvEngine.DEFAULT_FLUSH_THRESHOLD_BYTES, KvEngine.DEFAULT_COMPACTION_TRIGGER_COUNT, clock);

        long expiryEpochMillis = clock.instant().plusSeconds(5).toEpochMilli();
        engine.put(bytes("k"), bytes("v"), expiryEpochMillis);

        assertArrayEquals(bytes("v"), engine.get(bytes("k")));

        clock.advance(Duration.ofSeconds(4));
        assertArrayEquals(bytes("v"), engine.get(bytes("k")), "should still be live just before expiry");

        clock.advance(Duration.ofSeconds(2)); // now 1s past expiry
        assertNull(engine.get(bytes("k")), "should be treated as absent once the TTL has lapsed");

        engine.close();
    }

    @Test
    void deleteOnAnAlreadyExpiredKeyReportsItDidNotExist() throws IOException {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        KvEngine engine = new KvEngine(dataDir, KvEngine.DEFAULT_FLUSH_THRESHOLD_BYTES, KvEngine.DEFAULT_COMPACTION_TRIGGER_COUNT, clock);

        engine.put(bytes("k"), bytes("v"), clock.instant().plusSeconds(1).toEpochMilli());
        clock.advance(Duration.ofSeconds(2));

        assertFalse(engine.delete(bytes("k")));

        engine.close();
    }

    @Test
    void aKeyWithoutExAsPassedNeverExpires() throws IOException {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        KvEngine engine = new KvEngine(dataDir, KvEngine.DEFAULT_FLUSH_THRESHOLD_BYTES, KvEngine.DEFAULT_COMPACTION_TRIGGER_COUNT, clock);

        engine.put(bytes("k"), bytes("v"), 0);
        clock.advance(Duration.ofDays(365));

        assertTrue(engine.get(bytes("k")) != null);

        engine.close();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final class AdjustableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        AdjustableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new AdjustableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
