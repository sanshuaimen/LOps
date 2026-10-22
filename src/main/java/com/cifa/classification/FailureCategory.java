package com.cifa.classification;

public enum FailureCategory {
    /** Code compiled and ran, but behaved wrongly at runtime: test assertion
     * failures, NPEs, wrong results. The remediation is a code change — even when
     * the trigger looks environmental (e.g. an upstream 5xx the client fails to
     * handle) or the failure only manifests intermittently under load. */
    LOGIC_ERROR("code compiled and ran but behaved wrongly at runtime (test "
            + "assertion failures, NPEs, wrong results); the remediation is a code "
            + "change, even when the trigger looks environmental (e.g. an upstream "
            + "5xx the client should retry) or the failure is intermittent"),
    /** Environment/time/load-shaped failures whose remediation is retry,
     * quarantine or environment-side work rather than a code change: timeouts,
     * connection failures, DNS, OOM, order- or clock-dependent flakes. */
    INFRA_FLAKY("environment, time or load shaped failures whose remediation is "
            + "retry, quarantine or environment-side work rather than a code change "
            + "(timeouts, connection refused, DNS, OOM, order-dependent flakes)"),
    /** The build refused to produce artifacts: javac errors (cannot find symbol,
     * ambiguous overloads), unresolvable dependencies, missing parents — even
     * when the underlying mistake is in the code, the failure stage is the build. */
    DEPENDENCY_BUILD("the build refused to produce artifacts: javac compile "
            + "errors (cannot find symbol, ambiguous references), unresolvable "
            + "dependencies, missing artifacts or parent POMs — the failure stage "
            + "is build/compile, even when the underlying mistake is in the code"),
    /** The available evidence does not support any confident category above. */
    UNKNOWN("the available evidence does not support any confident diagnosis");

    private final String triageDefinition;

    FailureCategory(String triageDefinition) {
        this.triageDefinition = triageDefinition;
    }

    /** Single source of truth for the agent prompt's label semantics: the corpus
     * convention is stage-based (build refused vs runtime misbehaved vs
     * environment flaked), which a bare label name does not convey. */
    public String triageDefinition() {
        return triageDefinition;
    }
}
