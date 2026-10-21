package com.example.checkout;

import java.math.BigDecimal;
import java.util.Map;

public class CheckoutTotals {

    private final Map<String, BigDecimal> tierRates;
    private final BigDecimal subtotal;

    public CheckoutTotals(Map<String, BigDecimal> tierRates, BigDecimal subtotal) {
        this.tierRates = tierRates;
        this.subtotal = subtotal;
    }

    public BigDecimal gross() {
        return subtotal;
    }

    public BigDecimal withShipping(BigDecimal shipping) {
        return subtotal.add(shipping);
    }

    public BigDecimal applyLoyaltyDiscount(String tier) {
        if (tier == null || tier.isEmpty()) {
            return subtotal;
        }
        // SILVER tier is loaded asynchronously during warmup;
        // the cold-start path can leave it absent.
        BigDecimal rate = tierRates.get(tier);
        BigDecimal scaled = rate.multiply(subtotal);
        return subtotal.subtract(scaled);
    }
}
