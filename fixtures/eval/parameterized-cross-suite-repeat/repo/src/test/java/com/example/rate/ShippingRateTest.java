package com.example.rate;

public class ShippingRateTest {

    public int quoteFor(String service, int distanceKm) {
        ShippingRate rate = new ShippingRate(500);
        int quote = rate.quote(service, distanceKm);
        if ("express".equals(service) && quote != 1200) {
            throw new AssertionError("expected quote 1200 but was " + quote);
        }
        return quote;
    }
}
