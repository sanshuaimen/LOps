package com.example.report;

import com.example.billing.PriceCalculator;

public class ReportService {
    public String render() {
        PriceCalculator calculator = new PriceCalculator();
        return calculator.summary();
    }
}
