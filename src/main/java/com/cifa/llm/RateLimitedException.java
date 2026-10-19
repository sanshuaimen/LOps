package com.cifa.llm;

import java.io.IOException;

/**
 * The model API answered HTTP 429: retryable, but a distinct failure class —
 * the trace failure category ("rate_limited") and any future backoff policy
 * key off this type, not off message text.
 */
public class RateLimitedException extends IOException {

    public RateLimitedException(String message) {
        super(message);
    }
}
