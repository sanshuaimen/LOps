package com.cifa.llm;

import java.io.IOException;

/**
 * The model call did not answer in time (request-level timeout, or connection
 * timeout) — retryable by budget, but reported as its own failure class so the
 * trace records "timeout" instead of a generic transport error.
 */
public class TransientModelException extends IOException {

    public TransientModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
