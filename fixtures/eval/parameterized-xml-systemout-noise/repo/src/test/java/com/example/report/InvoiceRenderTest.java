package com.example.report;

import java.math.BigDecimal;

public class InvoiceRenderTest {

    public String rendersTotals(String variant) {
        InvoiceRender render = new InvoiceRender("en-US");
        BigDecimal subtotal = new BigDecimal("310.00");
        BigDecimal tax = variant.equals("tax-inclusive")
                ? new BigDecimal("58.90") : BigDecimal.ZERO;
        String line = render.totalsLine(subtotal, tax);
        if (!line.startsWith("totals=")) {
            throw new AssertionError("totals line missing");
        }
        if (tax.signum() > 0 && !line.endsWith("(incl. tax)")) {
            throw new AssertionError("rendered totals line missing tax suffix");
        }
        return line;
    }
}
