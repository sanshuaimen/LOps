package com.cifa.eval;

import com.cifa.llm.FakeLLMProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Produces the first Baseline-vs-Agent comparison report over the golden
 * corpus (fake providers: chain proof only) and checks its structure.
 */
class ComparisonReportTest {

    private static final Path GIT_FIXTURE =
            Path.of("fixtures/eval/git-regression-checkout/repo");
    private static final Path REVERT_FIXTURE =
            Path.of("fixtures/eval/git-revert-regression/repo");
    private static final String CHECKOUT_SAFE = """
            package com.example.checkout;

            import java.math.BigDecimal;

            public class CheckoutService {

                public BigDecimal totalsWithTax(BigDecimal subtotal, BigDecimal taxRate) {
                    BigDecimal rate = taxRate == null ? new BigDecimal("0.07") : taxRate;
                    if (subtotal.signum() < 0) {
                        throw new IllegalArgumentException("negative subtotal");
                    }
                    if (rate.signum() < 0) {
                        throw new IllegalArgumentException("negative tax rate");
                    }
                    return subtotal.multiply(rate);
                }
            }
            """;
    private static final String CHECKOUT_REGRESSED = """
            package com.example.checkout;

            import java.math.BigDecimal;

            public class CheckoutService {

                // a7f3c21 (2026-10-02) removed the default tax table;
                // callers passing a null taxRate now crash here
                public BigDecimal totalsWithTax(BigDecimal subtotal, BigDecimal taxRate) {
                    if (subtotal.signum() < 0) {
                        throw new IllegalArgumentException("negative subtotal");
                    }
                    if (taxRate.signum() < 0) {
                        throw new IllegalArgumentException("negative tax rate");
                    }
                    return subtotal.multiply(taxRate);
                }
            }
            """;
    private static final String CHECKOUT_TEST = """
            package com.example.checkout;

            import java.math.BigDecimal;

            public class CheckoutServiceTest {

                public void totalsWithTax() {
                    CheckoutService service = new CheckoutService();
                    if (service.totalsWithTax(new BigDecimal("100"), null).signum() != 0) {
                        throw new AssertionError("expected zero-tax total");
                    }
                }
            }
            """;

    // git-revert-regression: the guard from the fix commit exists only in the
    // middle of history — HEAD is the accidental revert, whose line 6 unboxes a
    // null discountCents (the frame the case's build.log cites).
    private static final String REVERT_REGRESSED = """
            package com.example.refund;

            public class RefundCalculator {

                public int refundCents(int paidCents, Integer discountCents) {
                    int base = paidCents - discountCents;
                    if (base < 0) {
                        throw new IllegalArgumentException("refund exceeds paid amount");
                    }
                    return base;
                }
            }
            """;
    private static final String REVERT_SAFE = """
            package com.example.refund;

            public class RefundCalculator {

                public int refundCents(int paidCents, Integer discountCents) {
                    int discount = discountCents == null ? 0 : discountCents;
                    int base = paidCents - discount;
                    if (base < 0) {
                        throw new IllegalArgumentException("refund exceeds paid amount");
                    }
                    return base;
                }
            }
            """;
    private static final String REVERT_TEST = """
            package com.example.refund;

            public class RefundCalculatorTest {

                public void refundsWithoutDiscount() {
                    RefundCalculator calculator = new RefundCalculator();
                    if (calculator.refundCents(5000, null) != 5000) {
                        throw new AssertionError("expected full refund when no discount");
                    }
                }
            }
            """;

    /**
     * The whole fixture repo is GENERATED (gitignored: git refuses to track
     * files inside a nested repository): sources, tests and the two-commit
     * history are materialized deterministically on first use — identical on
     * dev boxes and CI.
     */
    @org.junit.jupiter.api.BeforeAll
    static void materializeGitHistoryFixture() throws Exception {
        materializeCheckoutFixture();
        materializeRevertFixture();
    }

    private static void materializeCheckoutFixture() throws Exception {
        if (Files.exists(GIT_FIXTURE.resolve("src/main/java/com/example/checkout/CheckoutService.java"))) {
            return;
        }
        Path service = GIT_FIXTURE.resolve("src/main/java/com/example/checkout/CheckoutService.java");
        Path serviceTest = GIT_FIXTURE.resolve("src/test/java/com/example/checkout/CheckoutServiceTest.java");
        Files.createDirectories(service.getParent());
        Files.createDirectories(serviceTest.getParent());
        Files.writeString(serviceTest, CHECKOUT_TEST);
        exec(GIT_FIXTURE, "git", "init", "-q");
        exec(GIT_FIXTURE, "git", "config", "user.email", "ci@example.com");
        exec(GIT_FIXTURE, "git", "config", "user.name", "CI Fixture");
        Files.writeString(service, CHECKOUT_SAFE);
        exec(GIT_FIXTURE, "git", "add", "-A");
        exec(GIT_FIXTURE, "git", "commit", "-qm", "initial: default tax table covers null taxRate");
        Files.writeString(service, CHECKOUT_REGRESSED);
        exec(GIT_FIXTURE, "git", "add", "-A");
        exec(GIT_FIXTURE, "git", "commit", "-qm", "remove default tax table (a7f3c21)");
    }

    /** Three commits on purpose: HEAD is an accidental revert of the fix, which is the regression. */
    private static void materializeRevertFixture() throws Exception {
        if (Files.exists(REVERT_FIXTURE.resolve("src/main/java/com/example/refund/RefundCalculator.java"))) {
            return;
        }
        Path calculator = REVERT_FIXTURE.resolve("src/main/java/com/example/refund/RefundCalculator.java");
        Path calculatorTest = REVERT_FIXTURE.resolve("src/test/java/com/example/refund/RefundCalculatorTest.java");
        Files.createDirectories(calculator.getParent());
        Files.createDirectories(calculatorTest.getParent());
        Files.writeString(calculatorTest, REVERT_TEST);
        exec(REVERT_FIXTURE, "git", "init", "-q");
        exec(REVERT_FIXTURE, "git", "config", "user.email", "ci@example.com");
        exec(REVERT_FIXTURE, "git", "config", "user.name", "CI Fixture");
        Files.writeString(calculator, REVERT_REGRESSED);
        exec(REVERT_FIXTURE, "git", "add", "-A");
        exec(REVERT_FIXTURE, "git", "commit", "-qm", "initial: refunds require an explicit discount");
        Files.writeString(calculator, REVERT_SAFE);
        exec(REVERT_FIXTURE, "git", "add", "-A");
        exec(REVERT_FIXTURE, "git", "commit", "-qm", "fix: default missing discount to zero (4d9e112)");
        Files.writeString(calculator, REVERT_REGRESSED);
        exec(REVERT_FIXTURE, "git", "add", "-A");
        exec(REVERT_FIXTURE, "git", "commit", "-qm", "Revert \"fix: default missing discount to zero (4d9e112)\"");
    }

    private static void exec(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), () -> String.join(" ", command) + " failed: " + output);
    }

    @Test
    void buildsAndWritesComparisonReportOverWholeCorpus() throws Exception {
        List<GoldenCase> cases = GoldenCase.loadDirectory(Path.of("fixtures/eval"));
        EvalRunner runner = new EvalRunner();

        EvalReport baseline = runner.runBaseline(cases, new FakeLLMProvider());
        EvalReport agent = runner.runAgent(cases, ComparisonReportTest::providerFor);
        assertEquals(cases.size(), agent.results().size());
        assertEquals("", agent.results().stream()
                .filter(result -> !result.error().isEmpty())
                .findFirst()
                .map(EvalResult::error)
                .orElse(""), () -> "agent eval produced errors");

        ComparisonReport report = new ComparisonReport(baseline, agent);
        Path written = report.writeTo(Path.of("target/eval-reports"), "comparison-batch1");
        String markdown = Files.readString(written);

        assertTrue(markdown.contains("Baseline vs Agent comparison report"));
        assertTrue(markdown.contains("do NOT represent real model performance"), "disclaimer missing");
        assertTrue(markdown.contains("| category accuracy"));
        assertTrue(markdown.contains("| full-sweep rate"));
        assertTrue(markdown.contains("Tool-path distribution"));
        // All four independent investigation directions must actually appear in the
        // agent's tool paths — that is the path-sensitivity thesis, now with data.
        assertTrue(markdown.contains("dependency_analysis"), "dependency direction missing");
        assertTrue(markdown.contains("source_symbol_search"), "symbol-search direction missing");
        assertTrue(markdown.contains("flaky_history"), "flaky-history direction missing");
        assertTrue(markdown.contains("git_history"), "git-regression direction missing");
        // With every required tool invoked by its scripted case, the agent must pass the
        // path gate everywhere; the tool-less baseline cannot.
        assertEquals(1.0, agent.requiredToolsSatisfiedRate(), 1e-9);
        assertTrue(baseline.requiredToolsSatisfiedRate() < 1.0);
    }

    /**
     * Cases with a recorded agent-script.json replay it; everything else runs
     * the unscripted fake, which ends in an honest UNKNOWN.
     */
    private static com.cifa.llm.LLMProvider providerFor(GoldenCase goldenCase) {
        Path script = goldenCase.logPath().getParent().resolve("agent-script.json");
        if (Files.exists(script)) {
            try {
                return FakeLLMProvider.recorded(script);
            } catch (Exception e) {
                throw new IllegalStateException("bad script for " + goldenCase.id(), e);
            }
        }
        return new FakeLLMProvider();
    }
}
