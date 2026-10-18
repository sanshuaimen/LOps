package com.example;

import java.math.BigDecimal;

public class UserServiceTest {

    public void testPremiumDiscount() {
        UserService service = new UserService();
        // Regression: passing null here blows up inside calculateDiscount.
        BigDecimal discount = service.calculateDiscount(null);
        if (discount.compareTo(new BigDecimal("0.20")) != 0) {
            throw new AssertionError("expected premium discount");
        }
    }
}
