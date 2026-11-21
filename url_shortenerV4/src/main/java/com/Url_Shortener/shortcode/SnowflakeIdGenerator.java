package com.Url_Shortener.shortcode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Twitter Snowflake style ID generator.
 *
 * <pre>
 *  0 | 41 bits: ms since EPOCH | 10 bits: worker id | 12 bits: sequence
 * </pre>
 *
 * IDs are unique as long as every running instance has a different worker id
 * (app.snowflake.worker-id, 0-1023), and they grow over time. 41 bits of
 * milliseconds last about 69 years from EPOCH; up to 4096 IDs per millisecond
 * per worker.
 */
@Component
public class SnowflakeIdGenerator {

    /** 2026-01-01T00:00:00Z. Never change this: earlier IDs would be reissued. */
    static final long EPOCH = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    private static final int WORKER_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    /** A clock step back up to this size is waited out; larger ones fail fast. */
    private static final long MAX_BACKWARD_MS = 5;

    private final long workerId;
    private final Clock clock;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    @Autowired
    public SnowflakeIdGenerator(@Value("${app.snowflake.worker-id:0}") long workerId) {
        this(workerId, Clock.systemUTC());
    }

    SnowflakeIdGenerator(long workerId, Clock clock) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "app.snowflake.worker-id must be between 0 and " + MAX_WORKER_ID + ", was " + workerId);
        }
        this.workerId = workerId;
        this.clock = clock;
    }

    public synchronized long nextId() {
        long timestamp = clock.millis();

        if (timestamp < lastTimestamp) {
            long drift = lastTimestamp - timestamp;
            if (drift > MAX_BACKWARD_MS) {
                throw new IllegalStateException(
                        "Clock moved backwards by " + drift + " ms; refusing to generate IDs");
            }
            timestamp = waitUntil(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 4096 IDs used in this millisecond: move to the next one.
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << (WORKER_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence;
    }

    private long waitUntil(long target) {
        long now = clock.millis();
        while (now < target) {
            Thread.onSpinWait();
            now = clock.millis();
        }
        return now;
    }
}
