package com.example;

import java.util.List;

public class OrderService {

    private final PriceSource prices;

    public OrderService(PriceSource prices) {
        this.prices = prices;
    }

    public int computeTotal(List<String> skus) {
        int total = 0;
        for (String sku : skus) {
            Integer price = prices.unitPrice(sku);
            // regression: nullable price not handled
            total += price;
        }
        return total;
    }
}
