package com.example.reporting;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Buckets events by calendar day against the live clock. Runs started within
 * seconds of the UTC boundary observe a different day than the fixture
 * expects — a classic clock-dependent flake until a Clock is injected.
 */
public class DailyRollup {

    private final Clock clock;

    public DailyRollup(Clock clock) {
        this.clock = clock;
    }

    public Instant todayBucket(Instant event, long budgetMillis) {
        Instant start = clock.instant();
        while (Duration.between(start, clock.instant()).toMillis() < budgetMillis) {
            if (!event.atZone(clock.getZone()).toLocalDate()
                    .equals(clock.instant().atZone(clock.getZone()).toLocalDate())) {
                return event;
            }
        }
        throw new IllegalStateException("rollup window drifted (timed out waiting for the day bucket to close)");
    }
}
