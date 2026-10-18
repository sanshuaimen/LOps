package com.example.payments;

public class InvoicePrinter {

    private final InvoiceRepository repository;

    public InvoicePrinter(InvoiceRepository repository) {
        this.repository = repository;
    }

    public void print(String invoiceId) {
        // SpotBugs NP_NULL_ON_SOME_PATH: find() may return null
        Invoice invoice = repository.find(invoiceId);
        System.out.println(invoice.number());
    }
}
