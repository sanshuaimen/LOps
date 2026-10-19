package com.example.payments;

import com.example.money.AmountFormatter;
import java.math.BigDecimal;

public class PaymentGateway {

    private final AmountFormatter formatter;

    public PaymentGateway(AmountFormatter formatter) {
        this.formatter = formatter;
    }

    public String receipt(BigDecimal amount) {
        return "EUR " + formatter.formatAmount(amount);
    }
}
