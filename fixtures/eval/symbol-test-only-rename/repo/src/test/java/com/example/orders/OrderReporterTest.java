package com.example.orders;

public class OrderReporterTest {

    public int reportsTotal() {
        OrderReporter reporter = new OrderReporter(2500);
        // pre-rename field name still referenced here
        if (reporter.legacyTotal != 2500) {
            throw new AssertionError("total mismatch");
        }
        return reporter.legacyTotal;
    }
}
