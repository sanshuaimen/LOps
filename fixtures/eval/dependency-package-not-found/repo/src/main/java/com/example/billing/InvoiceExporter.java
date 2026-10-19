package com.example.billing;

import java.util.List;

import com.example.pricing.api.PriceFeed;

public class InvoiceExporter {

    private final PriceFeed priceFeed;

    public InvoiceExporter(PriceFeed priceFeed) {
        this.priceFeed = priceFeed;
    }

    public List<String> exportPending(java.util.List<java.math.BigDecimal> amounts) {
        return amounts.stream().map(amount -> priceFeed.rate(amount).toPlainString()).toList();
    }
}
