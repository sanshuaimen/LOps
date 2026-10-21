package com.example.billing;

import java.math.BigDecimal;

public class InvoicePrinter {

    private final String merchant;
    private final String numberPrefix;

    public InvoicePrinter(String merchant, String numberPrefix) {
        this.merchant = merchant;
        this.numberPrefix = numberPrefix;
    }

    public void printHeader(String number, String currency) {
        System.out.println(numberPrefix + " " + merchant + " " + number + " " + currency);
    }

    public void printTotals(BigDecimal total, String currency) {
        System.out.println("== totals ==");
        System.out.println("merchant: " + merchant);
        String number = numberPrefix + "-0007";
        printHeader(total, currency);
    }
}
