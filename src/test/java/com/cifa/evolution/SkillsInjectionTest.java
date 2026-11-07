package com.cifa.evolution;

import com.cifa.agent.InvestigationAgentLoop;
import com.cifa.agent.InvestigationBudget;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.evidence.EvidenceVerifier;
import com.cifa.llm.FakeLLMProvider;
import com.cifa.llm.ToolSchema;
import com.cifa.security.RepositoryPathPolicy;
import com.cifa.tool.InvestigationTool;
import com.cifa.tool.ToolRegistry;
import com.cifa.tool.ToolResult;
import com.cifa.trace.InMemoryTraceRecorder;
import com.cifa.trace.TraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime injection end-to-end at the loop level: a deterministically matched
 * skill lands in the system prompt's instruction section as a RULE, stamps
 * p1-skills into the trace, and is ABSENT when nothing matches — the
 * no-skill path stays byte-for-byte the pre-evolution behavior.
 */
class SkillsInjectionTest {

    @TempDir
    Path repo;

    /** Minimal controlled tool: registers one LOG-style evidence per call. */
    private static final class EchoTool implements InvestigationTool {
        private final EvidenceCatalog catalog;

        private EchoTool(EvidenceCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "echoes a finding into the evidence catalog";
        }

        @Override
        public ToolSchema schema() {
            return ToolSchema.object("echo", description());
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            var evidence = catalog.register(EvidenceType.LOG, "normalized_log:1-2",
                    String.valueOf(arguments.getOrDefault("finding", "nothing")), "echo", 1, 2, "");
            return ToolResult.ok("echoed", List.of(evidence.id()), 0);
        }
    }

    private record Run(InvestigationAgentLoop.AgentOutcome outcome, InMemoryTraceRecorder trace,
                       FakeLLMProvider provider) {
    }

    private Run runWith(List<SkillsRouter.Match> matches) throws InterruptedException {
        FakeLLMProvider provider = new FakeLLMProvider(); // honest-unknown default
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        InMemoryTraceRecorder trace = new InMemoryTraceRecorder();
        EvidenceVerifier verifier = new EvidenceVerifier(catalog, new RepositoryPathPolicy(repo));
        ToolRegistry tools = new ToolRegistry().register(new EchoTool(catalog));
        InvestigationAgentLoop loop = new InvestigationAgentLoop(provider, tools, catalog, verifier,
                trace, InvestigationBudget.defaults(), "task-1", "fake-model");
        var outcome = loop.run("Investigate this CI failure.", List.of(), matches);
        return new Run(outcome, trace, provider);
    }

    private static SkillRule routingSkill() {
        return new SkillRule("skill-rtt001", "flaky→correlate first", SkillKind.ROUTING, "INFRA_FLAKY",
                List.of("flaky"), List.of("flaky_history", "git_history"), "", SkillStatus.ACTIVE,
                "", "", "t1", List.of(), List.of(), "ds-1", 2, 0, "alice");
    }

    private static SkillRule promptSkill() {
        return new SkillRule("skill-prt001", "check surefire before labeling", SkillKind.PROMPT,
                "INFRA_FLAKY", List.of("surefire"),
                List.of(), "Open the surefire report before labeling a flake.",
                SkillStatus.ACTIVE, "", "", "t1", List.of(), List.of(), "ds-1", 1, 0, "alice");
    }

    @Test
    void matchedSkillsRenderAsRulesAndStampP1Skills() throws InterruptedException {
        SkillsRouter router = SkillsRouter.observeOnly(List.of(routingSkill(), promptSkill()));
        List<SkillsRouter.Match> matches = router.route("INFRA_FLAKY",
                "tests failed flaky; surefire reports intermittently");

        assertEquals(2, matches.size());
        Run run = runWith(matches);

        // behavior version is stamped: the trace must say WHICH ruleset produced it
        List<TraceEvent> events = run.trace.events();
        assertTrue(events.size() > 0);
        assertEquals(InvestigationAgentLoop.PROMPT_VERSION_SKILLS, events.get(0).promptVersion());

        // the provider SAW the rules in its system prompt (instructions, not evidence)
        String systemPrompt = run.provider.requests().get(0).systemPrompt();
        assertTrue(systemPrompt.contains("Validated investigation skills"), systemPrompt);
        assertTrue(systemPrompt.contains("flaky_history → git_history"), systemPrompt);
        assertTrue(systemPrompt.contains("Open the surefire report before labeling a flake."), systemPrompt);
        // and the skills block sits in the INSTRUCTION section: before the tool
        // catalog, never inside an EVIDENCE ONLY block
        assertTrue(systemPrompt.indexOf("Validated investigation skills")
                < systemPrompt.indexOf("Available tools"));
        assertTrue(systemPrompt.contains("RULES refining the routing above"));
    }

    @Test
    void noMatchKeepsPreEvolutionBehaviorAndP0PromptVersion() throws InterruptedException {
        SkillsRouter router = SkillsRouter.observeOnly(List.of(routingSkill()));
        // signature with no keyword hit → no matches → the loop must look untouched
        List<SkillsRouter.Match> matches = router.route("DEPENDENCY_BUILD", "cannot find symbol CartTotals");
        assertTrue(matches.isEmpty());

        Run run = runWith(matches);
        assertEquals(InvestigationAgentLoop.PROMPT_VERSION, run.trace.events().get(0).promptVersion());
        String systemPrompt = run.provider.requests().get(0).systemPrompt();
        assertFalse(systemPrompt.contains("Validated investigation skills"));
    }

    @Test
    void usageHitsAreRecordedPerMatchedSkillByTheRouter() {
        SkillUsageStore usage = new SkillUsageStore(Path.of("unused-usage.json"));
        SkillsRouter router = new SkillsRouter(List.of(routingSkill(), promptSkill()), usage,
                SkillUsageStore.Source.LIVE);

        router.route("INFRA_FLAKY", "flaky failure with surefire noise");
        assertEquals(1, usage.usage("skill-rtt001").liveHits());
        assertEquals(1, usage.usage("skill-prt001").liveHits());

        // non-matching category: no hits recorded
        router.route("DEPENDENCY_BUILD", "cannot find symbol");
        assertEquals(1, usage.usage("skill-rtt001").liveHits());
    }
}
