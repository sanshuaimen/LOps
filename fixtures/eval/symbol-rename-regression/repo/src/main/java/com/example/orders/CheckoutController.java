package com.example.orders;

public class CheckoutController {

    public String checkout(Order order) {
        int total = order.calcTotal();
        return "charged " + total;
    }
}
