package com.example.rate;

public class ShippingRate {

    private final int weightGrams;

    public ShippingRate(int weightGrams) {
        this.weightGrams = weightGrams;
    }

    public int quote(String service, int distanceKm) {
        int base = distanceKm * 2;
        if ("ground".equals(service)) {
            return base;
        }
        if ("express".equals(service)) {
            return base + expressSurcharge();
        }
        throw new IllegalArgumentException("unknown service " + service);
    }

    private int expressSurcharge() {
        return 0;
    }
}
