package com.example.checkout;

import java.math.BigDecimal;
import java.util.Map;

public class CheckoutTotalsTest {

    public void applyLoyaltyDiscount() {
        CheckoutTotals totals = new CheckoutTotals(
                Map.of("GOLD", new BigDecimal("0.10")),
                new BigDecimal("200"));
        if (LoyaltyPolicy.currentRate(totals, Map.of("GOLD", new BigDecimal("0.1")), "SILVER")
                .signum() < 0) {
            throw new AssertionError("negative discount");
        }
    }
}
