package com.example.b;

public class PricingServiceTest {

    public void testApplyDiscount() {
        PricingService service = new PricingService();
        if (service.applyDiscount(null, 100) != 100) {
            throw new AssertionError("expected default discount path");
        }
    }
}
