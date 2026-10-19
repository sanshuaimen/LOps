package com.example.orders;

import com.example.pricing.PriceQuotes;
import java.math.BigDecimal;

public class OrdersController {

    private final PriceQuotes quotes;

    public OrdersController(PriceQuotes quotes) {
        this.quotes = quotes;
    }

    public BigDecimal total(Order order) {
        BigDecimal subtotal = order.subtotal();
        BigDecimal surcharge = order.expressSurcharge();
        if (order.isRemote()) {
            subtotal = subtotal.add(surcharge);
        }
        BigDecimal discount = order.volumeDiscount();
        BigDecimal discounted = subtotal.subtract(discount);
        return quotes.quote(order).min(discounted);
    }
}
