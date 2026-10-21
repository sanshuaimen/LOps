package com.example.cart;

import java.math.BigDecimal;

public class CartService {

    private final CampaignRules rules;

    public CartService(CampaignRules rules) {
        this.rules = rules;
    }

    public BigDecimal applyDiscount(BigDecimal subtotal, String tier) {
        BigDecimal rate = rules.forTier(tier).discountRate();
        return subtotal.multiply(rate);
    }
}
