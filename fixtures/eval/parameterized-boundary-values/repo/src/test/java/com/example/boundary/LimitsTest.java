package com.example.boundary;

public class LimitsTest {

    public void acceptsWithinLimit(int boundary, int probe) {
        Limits limits = new Limits(boundary);
        if (!limits.accepts(boundary)) {
            throw new AssertionError("boundary rejected");
        }
        if (probe > boundary && limits.accepts(probe)) {
            throw new AssertionError("boundary " + boundary + " accepted " + probe);
        }
    }
}
