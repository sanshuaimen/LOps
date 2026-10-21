package com.example.orders;

public class OrderReporter {

    final int grandTotal;

    public OrderReporter(int grandTotal) {
        this.grandTotal = grandTotal;
    }

    public int asCents() {
        return grandTotal * 100;
    }
}
