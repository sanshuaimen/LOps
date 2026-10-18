package com.cifa.tool;

import java.util.List;
import java.util.Optional;

/**
 * Independent, read-only source of per-test run history (pass/fail, reruns,
 * related commits). Flaky conclusions must come from here — never from git
 * modification history re-labeled as flakiness.
 */
public interface FlakyHistorySource {

    Optional<FlakyRunHistory> load(String testName);

    static FlakyHistorySource empty() {
        return test -> Optional.empty();
    }
}
