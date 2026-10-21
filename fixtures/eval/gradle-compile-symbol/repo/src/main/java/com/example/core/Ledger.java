package com.example.core;

import java.math.BigDecimal;
import java.util.List;

/** Aggregates checkout ledger entries for the day report. */
public class Ledger {

    private final List<Entry> entries;
    private final String storeId;

    public Ledger(List<Entry> entries, String storeId) {
        this.entries = List.copyOf(entries);
        this.storeId = storeId;
    }

    public record Entry(String sku, int quantity, BigDecimal unitPrice) {
    }

    public BigDecimal grossTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (Entry entry : entries) {
            total = total.add(entry.unitPrice()
                    .multiply(BigDecimal.valueOf(entry.quantity())));
        }
        return total;
    }

    public int entryCount() {
        return entries.size();
    }

    public String storeId() {
        return storeId;
    }

    public BigDecimal netTotal() {
        BigDecimal gross = grossTotal();
        BigDecimal discount = promoDiscount();
        BigDecimal net = gross.subtract(discount);
        return cartTotals.sum();
    }

    private BigDecimal promoDiscount() {
        return BigDecimal.ZERO;
    }
}
