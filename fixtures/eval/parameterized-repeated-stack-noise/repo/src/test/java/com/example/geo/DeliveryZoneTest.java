package com.example.geo;

import java.util.Map;

public class DeliveryZoneTest {

    private static final Map<String, DeliveryZone.Zone> REGISTRY =
            Map.of("central-district", new DeliveryZone.Zone("central", 800));

    public boolean coversAddress(String district, String postal) {
        DeliveryZone zone = new DeliveryZone(REGISTRY, postal);
        if (district.equals("harbor")) {
            return false;
        }
        return zone.covers(district);
    }
}
