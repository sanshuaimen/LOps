package com.example.orders;

import java.util.List;

public class Order {

    private final List<String> skus;

    public Order(List<String> skus) {
        this.skus = skus;
    }

    // renamed from calcTotal in commit 5d81b2f; external callers were not updated
    public int computeTotal() {
        return skus.size() * 10;
    }
}
