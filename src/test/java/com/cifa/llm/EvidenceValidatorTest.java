package com.cifa.llm;

import com.cifa.classification.FailureCategory;
import com.cifa.context.AnalysisContext;
import com.cifa.parsing.StackFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceValidatorTest {

    @Test
    void flagsEvidenceNotPresentInContext() {
        AnalysisContext context = new AnalysisContext(
                "java.lang.NullPointerException",
                "Cannot invoke CustomerType.getDiscountRate()",
                List.of(),
                List.of(new StackFrame("com.example.UserService", "calculateDiscount", "UserService.java", 9)),
                "double rate = customer.getType().getDiscountRate();",
                "+        return base * rate * bulkImportMultiplier(customer);",
                FailureCategory.LOGIC_ERROR,
                List.of()
        );
        AnalysisResult result = new AnalysisResult(
                "NPE in calculateDiscount",
                List.of(
                        "UserService.calculateDiscount at line 9 dereferences a null discount rate",
                        "Commit deadbeef1234 by Alice Smith rewrote the pricing engine last Tuesday"
                ),
                "Add a null check before calling getDiscountRate()",
                80,
                false
        );

        List<String> unverified = EvidenceValidator.findUnverified(result, context);

        assertEquals(1, unverified.size());
        assertEquals("Commit deadbeef1234 by Alice Smith rewrote the pricing engine last Tuesday", unverified.get(0));
    }
}
