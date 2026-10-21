package com.example.payments;

import java.util.Map;
import java.util.Set;

public class RefundServiceTest {

    private static final Set<String> APPROVED = Set.of("pay-1", "pay-2");

    public int refundsApprovedAmount(String paymentId, int requested) {
        RefundService service = new RefundService(APPROVED, Map.of("pay-1", 300));
        if (requested > 5000) {
            throw new IllegalArgumentException("over limit");
        }
        return service.refundCents(paymentId, requested);
    }
}
