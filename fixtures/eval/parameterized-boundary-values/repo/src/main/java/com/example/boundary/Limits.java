package com.example.boundary;

public class Limits {

    private final int maxValue;

    public Limits(int maxValue) {
        this.maxValue = maxValue;
    }

    public boolean accepts(int value) {
        if (value < 0) {
            return false;
        }
        if (maxValue == Integer.MAX_VALUE) {
            return true;
        }
        return value <= maxValue + 1;
    }
}
