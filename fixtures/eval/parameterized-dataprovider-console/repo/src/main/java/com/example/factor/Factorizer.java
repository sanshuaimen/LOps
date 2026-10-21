package com.example.factor;

public class Factorizer {

    private final int step;

    public Factorizer(int step) {
        this.step = step;
    }

    public int smallestFactor(int value) {
        int candidate = step;
        while (candidate * candidate <= value) {
            if (value % candidate == 0) {
                return candidate;
            }
            candidate += step == 0 ? 0 : 2;
        }
        return value;
    }
}
