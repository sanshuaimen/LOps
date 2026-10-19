package com.example.money;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

public final class AmountFormatter {

    /** render() replaced formatAmount() in the 1.4 API cleanup. */
    public String render(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance(Locale.GERMANY).format(amount);
    }
}
