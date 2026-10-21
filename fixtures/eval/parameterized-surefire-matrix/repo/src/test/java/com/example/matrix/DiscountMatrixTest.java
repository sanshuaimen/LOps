package com.example.matrix;

import java.math.BigDecimal;

public class DiscountMatrixTest {

    public BigDecimal roundsDiscount(String listPrice, BigDecimal expected) {
        DiscountMatrix matrix = new DiscountMatrix(new BigDecimal("0.10"));
        BigDecimal actual = matrix.discounted(new BigDecimal(listPrice));
        if (actual.compareTo(expected) != 0) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
        return actual;
    }
}
