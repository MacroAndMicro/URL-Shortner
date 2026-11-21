package com.Url_Shortener.shortcode;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeIdGeneratorTest {

    /** A clock whose time the test sets by hand. */
    private static final class ManualClock extends Clock {
        long millis;

        ManualClock(long millis) {
            this.millis = millis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void idsAreUniqueAndIncreasing() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0);
        Set<Long> seen = new HashSet<>();
        long previous = -1;
        for (int i = 0; i < 100_000; i++) {
            long id = generator.nextId();
            assertThat(id).isGreaterThan(previous);
            assertThat(seen.add(id)).isTrue();
            previous = id;
        }
    }

    @Test
    void idsAreUniqueAcrossThreads() throws InterruptedException {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0);
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                for (int i = 0; i < 10_000; i++) {
                    seen.add(generator.nextId());
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).hasSize(80_000);
    }

    @Test
    void idPacksTimestampWorkerAndSequence() {
        ManualClock clock = new ManualClock(SnowflakeIdGenerator.EPOCH + 1000);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(5, clock);

        long first = generator.nextId();
        long second = generator.nextId();

        assertThat(first >>> 22).isEqualTo(1000);
        assertThat((first >>> 12) & 0x3FF).isEqualTo(5);
        assertThat(first & 0xFFF).isZero();
        assertThat(second & 0xFFF).isEqualTo(1);
    }

    @Test
    void differentWorkersNeverCollideInTheSameMillisecond() {
        ManualClock clock = new ManualClock(SnowflakeIdGenerator.EPOCH + 1000);
        assertThat(new SnowflakeIdGenerator(1, clock).nextId())
                .isNotEqualTo(new SnowflakeIdGenerator(2, clock).nextId());
    }

    @Test
    void largeClockStepBackwardsFails() {
        ManualClock clock = new ManualClock(SnowflakeIdGenerator.EPOCH + 1000);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0, clock);
        generator.nextId();

        clock.millis -= 1000;

        assertThatThrownBy(generator::nextId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void workerIdOutOfRangeIsRejected() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(SnowflakeIdGenerator.MAX_WORKER_ID + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentIdsEncodeToTenOrElevenBase62Characters() {
        String code = Base62.encode(new SnowflakeIdGenerator(0).nextId());
        assertThat(code).matches("[0-9A-Za-z]{10,11}").matches(ShortCode.REGEX);
    }
}
