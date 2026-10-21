package com.example.payments;

import java.util.Map;
import java.util.Set;

public class RefundService {

    private final Set<String> approvedPayments;
    private final Map<String, Integer> feeTable;

    public RefundService(Set<String> approvedPayments, Map<String, Integer> feeTable) {
        this.approvedPayments = approvedPayments;
        this.feeTable = feeTable;
    }

    public int refundCents(String paymentId, int requestedCents) {
        if (requestedCents <= 0) {
            throw new IllegalArgumentException("non-positive refund");
        }
        if (!approvedPayments.contains(paymentId)) {
            return 0;
        }
        int feeCents = feeTable.getOrDefault(paymentId, requestedCents);
        return requestedCents - feeCents;
    }
}
