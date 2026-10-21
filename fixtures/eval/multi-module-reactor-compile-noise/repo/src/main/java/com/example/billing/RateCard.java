package com.example.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

public class RateCard {

    private final BigDecimal baseRate;
    private final LocalDate effectiveFrom;

    public RateCard(BigDecimal baseRate, LocalDate effectiveFrom) {
        this.baseRate = baseRate;
        this.effectiveFrom = effectiveFrom;
    }

    public BigDecimal withVat() {
        return baseRate.multiply(VAT_RATE);
    }
}
