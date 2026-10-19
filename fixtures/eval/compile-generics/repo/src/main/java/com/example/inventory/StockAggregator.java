package com.example.inventory;

import java.util.List;

public class StockAggregator {

    public int totalUnits(List<String> skuCodes, List<Integer> quantities) {
        List<Integer> codes = skuCodes;
        if (codes.size() != quantities.size()) {
            throw new IllegalArgumentException("sku/quantity size mismatch");
        }
        return quantities.stream().mapToInt(Integer::intValue).sum();
    }
}
