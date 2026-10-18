package com.example.b;

public class PricingService {

    public int applyDiscount(String customerType, int amount) {
        // regression: null customerType no longer defaulted to STANDARD
        if (customerType.length() == 0) {
            return amount;
        }
        return "VIP".equals(customerType) ? amount / 2 : amount;
    }
}
