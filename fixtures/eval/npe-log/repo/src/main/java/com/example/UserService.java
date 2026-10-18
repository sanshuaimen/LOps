package com.example;

import java.math.BigDecimal;

public class UserService {

    public BigDecimal calculateDiscount(Customer customer) {
        CustomerType type = customer == null ? null : customer.type();
        if (type == null) {
            throw new NullPointerException("Customer type is null");
        }
        if (type == CustomerType.PREMIUM) {
            return new BigDecimal("0.20");
        }
        if (type == CustomerType.STANDARD) {
            return new BigDecimal("0.05");
        }
        throw new IllegalArgumentException("unknown customer type: " + type);
    }
}
