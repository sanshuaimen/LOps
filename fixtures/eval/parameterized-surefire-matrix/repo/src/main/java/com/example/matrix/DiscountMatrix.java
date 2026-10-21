package com.example.matrix;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DiscountMatrix {

    private final BigDecimal rate;

    public DiscountMatrix(BigDecimal rate) {
        this.rate = rate;
    }

    public BigDecimal discounted(BigDecimal list) {
        BigDecimal raw = list.subtract(list.multiply(rate));
        return round(raw);
    }

    BigDecimal round(BigDecimal value) {
        return value.setScale(1, RoundingMode.DOWN);
    }
}
