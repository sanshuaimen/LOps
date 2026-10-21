package com.example.cart;

import java.math.BigDecimal;

public class CampaignRules {

    public CampaignRule forTier(String tier) {
        if (!"standard".equals(tier)) {
            return null;
        }
        return new CampaignRule(new BigDecimal("0.10"));
    }
}
