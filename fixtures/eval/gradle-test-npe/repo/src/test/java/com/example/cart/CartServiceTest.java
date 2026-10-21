package com.example.cart;

import java.math.BigDecimal;

public class CartServiceTest {

    public void applyDiscountForPremium() {
        CartService service = new CartService(new CampaignRules());
        if (service.applyDiscount(new BigDecimal("100"), "premium").signum() < 0) {
            throw new AssertionError("negative discount");
        }
    }
}
