package com.example.cart;

import java.util.ArrayList;
import java.util.List;

public class CartTest {

    private final List<String> items = new ArrayList<>();

    public void totalsAfterAdd() {
        items.add("sku-1");
        int total = checkoutTotal(items);
        if (total != 1) {
            throw new AssertionError("expected one item");
        }
    }
}
