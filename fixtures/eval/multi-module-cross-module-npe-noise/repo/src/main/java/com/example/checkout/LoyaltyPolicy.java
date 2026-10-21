package com.example.checkout;

import java.math.BigDecimal;
import java.util.Map;

public final class LoyaltyPolicy {

    private LoyaltyPolicy() {
    }

    static void validate(String tier) {
        if (tier != null && tier.isBlank()) {
            throw new IllegalArgumentException("blank tier");
        }
    }

    static BigDecimal currentRate(CheckoutTotals totals, Map<String, BigDecimal> rates, String tier) {
        validate(tier);
        if (rates.isEmpty()) {
            return totals.gross();
        }
        return totals.applyLoyaltyDiscount(tier);
    }
}
