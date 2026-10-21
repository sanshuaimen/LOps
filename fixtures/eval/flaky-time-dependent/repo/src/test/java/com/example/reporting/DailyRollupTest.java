package com.example.reporting;

import java.time.Clock;
import java.time.Instant;

public class DailyRollupTest {

    public Instant rollsUpTodayBucket() {
        DailyRollup rollup = new DailyRollup(Clock.systemUTC());
        Instant event = Instant.parse("2026-10-20T23:59:58Z");
        Instant bucketed = rollup.todayBucket(event, 2000);
        if (bucketed.isAfter(Instant.now())) {
            throw new AssertionError("bucket drifted into the future");
        }
        return bucketed;
    }
}
