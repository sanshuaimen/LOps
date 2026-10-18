package com.example;

import java.util.List;

public class OrderServiceTest {

    public void computesTotal() {
        OrderService service = new OrderService(sku -> null);
        if (service.computeTotal(List.of("A")) != 0) {
            throw new AssertionError("expected zero total");
        }
    }
}
