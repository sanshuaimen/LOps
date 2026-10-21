package com.example.report;

import java.math.BigDecimal;

public class InvoiceRender {

    private final String locale;

    public InvoiceRender(String locale) {
        this.locale = locale;
    }

    public String lineItem(String sku, BigDecimal price) {
        return sku + " " + price.toPlainString();
    }

    public String totalsLine(BigDecimal subtotal, BigDecimal tax) {
        BigDecimal total = subtotal.add(tax);
        if (tax.signum() == 0) {
            return "totals=" + total.toPlainString();
        }
        String suffix = locale.startsWith("en") ? "" : " (incl. tax)";
        return "totals=" + total.toPlainString() + suffix;
    }
}
