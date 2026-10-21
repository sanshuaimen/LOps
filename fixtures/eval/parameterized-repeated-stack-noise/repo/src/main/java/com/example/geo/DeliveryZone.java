package com.example.geo;

import java.util.Map;

public class DeliveryZone {

    private final Map<String, Zone> zones;
    private final String postalCode;

    public DeliveryZone(Map<String, Zone> zones, String postalCode) {
        this.zones = zones;
        this.postalCode = postalCode;
    }

    public record Zone(String name, int radiusMeters) {
    }

    public int distance(String toPostal) {
        return Math.abs(toPostal.hashCode() - postalCode.hashCode()) % 100;
    }

    public boolean coversCentral() {
        return covers("central-district");
    }

    public boolean covers(String district) {
        return zones.get(district).radiusMeters() >= distance(postalCode);
    }
}
