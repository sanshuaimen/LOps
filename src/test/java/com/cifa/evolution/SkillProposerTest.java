package com.cifa.evolution;

import com.cifa.llm.LLMProvider;
import com.cifa.llm.LLMRequest;
import com.cifa.llm.LLMResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Proposer's contract: gated candidates with deterministic safety checks
 * (tool whitelist, real categories, signature-keyword interlock), honest skips
 * on garbage/failures/budget exhaustion, and negative-knowledge awareness in
 * the prompt (rejected paths + active index are shown, not invented).
 */
class SkillProposerTest {

    /** Minimal scripted provider with controllable token counts (the budget tests need them). */
    private static final class ScriptedProvider implements LLMProvider {
        private final Deque<Object> script = new ArrayDeque<>();
        int totalTokens = 0;
        final List<LLMRequest> requests = new java.util.ArrayList<>();

        ScriptedProvider enqueue(String content) {
            script.add(content);
            return this;
        }

        ScriptedProvider enqueueFailure(IOException failure) {
            script.add(failure);
            return this;
        }

        @Override
        public LLMResponse invoke(LLMRequest request) throws IOException, InterruptedException {
            requests.add(request);
            Object next = script.poll();
            if (next instanceof IOException ioException) {
                throw ioException;
            }
            if (next instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            String content = next == null ? "" : next.toString();
            totalTokens += 100;
            return new LLMResponse(content, null, 60, 40, 0);
        }

        @Override
        public com.cifa.llm.AnalysisResult analyze(com.cifa.context.AnalysisContext context) {
            throw new UnsupportedOperationException("proposer never uses the baseline path");
        }
    }

    private static final Set<String> TOOLS = Set.of("git_history", "source_symbol_search",
            "source_location", "dependency_analysis", "surefire_report", "flaky_history", "failure_evidence");

    private static final String TRACE = "trace text about a flaky test that correlates with commit abc";

    private SkillProposer proposer(LLMProvider provider, ProposalBudget budget) {
        return new SkillProposer(provider, "fake", TOOLS, budget);
    }

    @Test
    void routingProposalPassesGatesAndCarriesProvenance() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        provider.enqueue("""
                {"propose":true,"kind":"ROUTING","name":"flaky-correlation-first",
                 "category":"INFRA_FLAKY","symptomKeywords":["flaky","intermittent"],
                 "routingTools":["flaky_history","git_history"],
                 "reason":"flaky labels need correlation before conclusion"}""");
        SkillProposer.Proposal proposal = proposer(provider, ProposalBudget.defaults()).propose(
                TRACE, "INFRA_FLAKY (judge supported)", List.of(), List.of(),
                "Tests failed intermittently flaky in CI", "trace-agent-1.jsonl");

        assertNull(proposal.skipReason());
        assertEquals(SkillKind.ROUTING, proposal.candidate().kind());
        assertEquals(List.of("flaky_history", "git_history"), proposal.candidate().routingTools());
        assertEquals("trace-agent-1.jsonl", proposal.candidate().sourceTrace());
        assertTrue(proposal.candidate().id().startsWith("skill-"));
        // the prompt showed the model the real evidence: signature + trace tail
        String prompt = provider.requests.get(0).userPrompt();
        assertTrue(prompt.contains("flaky"));
        assertTrue(prompt.contains("(none yet)"));
    }

    @Test
    void unknownToolNameIsRejectedDeterministically() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        provider.enqueue("""
                {"propose":true,"kind":"ROUTING","name":"hallucinated-tool",
                 "category":"LOGIC_ERROR","symptomKeywords":["regression"],
                 "routingTools":["run_arbitrary_shell"],"reason":"should be gated"}""");
        SkillProposer.Proposal proposal = proposer(provider, ProposalBudget.defaults()).propose(
                TRACE, "LOGIC_ERROR", List.of(), List.of(), "regression suspected", "t1");

        assertNull(proposal.candidate());
        assertTrue(proposal.skipReason().contains("known-tool list"), proposal.skipReason());
    }

    @Test
    void keywordSignatureInterlockRejectsUnmatchableSkills() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        provider.enqueue("""
                {"propose":true,"kind":"PROMPT","name":"irrelevant",
                 "category":"LOGIC_ERROR","symptomKeywords":["postgresql"],
                 "instruction":"check the database","reason":"no relation to the signature"}""");
        SkillProposer.Proposal proposal = proposer(provider, ProposalBudget.defaults()).propose(
                TRACE, "LOGIC_ERROR", List.of(), List.of(),
                "surefire reports flaky failure", "t1");

        assertNull(proposal.candidate());
        assertTrue(proposal.skipReason().contains("cannot match its own source case"), proposal.skipReason());
    }

    @Test
    void garbageAndFailuresDegradeToHonestSkips() throws Exception {
        ScriptedProvider garbage = new ScriptedProvider();
        garbage.enqueue("not json at all");
        SkillProposer.Proposal bad = proposer(garbage, ProposalBudget.defaults()).propose(
                TRACE, "", List.of(), List.of(), "flaky", "t1");
        assertNull(bad.candidate());
        assertTrue(bad.skipReason().contains("unparseable"));

        ScriptedProvider failing = new ScriptedProvider();
        failing.enqueueFailure(new IOException("connection reset"));
        SkillProposer.Proposal failed = proposer(failing, ProposalBudget.defaults()).propose(
                TRACE, "", List.of(), List.of(), "flaky", "t1");
        assertNull(failed.candidate());
        assertTrue(failed.skipReason().contains("propose call failed"));
    }

    @Test
    void explicitSkipIsAnHonestOutcome() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        provider.enqueue("""
                {"propose":false,"reason":"case-specific: the typo is not a class of failures"}""");
        SkillProposer.Proposal proposal = proposer(provider, ProposalBudget.defaults()).propose(
                TRACE, "", List.of(), List.of(), "flaky", "t1");
        assertNull(proposal.candidate());
        assertTrue(proposal.skipReason().contains("case-specific"));
    }

    @Test
    void budgetExhaustionSkipsWithoutCallingTheModel() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        provider.enqueue("""
                {"propose":true,"kind":"PROMPT","name":"one","category":"LOGIC_ERROR",
                 "symptomKeywords":["flaky"],"instruction":"do x","reason":"r"}""");
        ProposalBudget budget = new ProposalBudget(100);
        SkillProposer p = proposer(provider, budget);

        SkillProposer.Proposal first = p.propose(TRACE, "", List.of(), List.of(), "flaky", "t1");
        assertNotNull(first.candidate());
        assertEquals(100, budget.spentTokens());

        // budget exhausted: the second call never reaches the provider
        SkillProposer.Proposal second = p.propose(TRACE, "", List.of(), List.of(), "flaky", "t2");
        assertNull(second.candidate());
        assertTrue(second.skipReason().contains("budget exhausted"));
        assertEquals(1, provider.requests.size());
    }

    @Test
    void promptCarriesActiveIndexAndRejectedPaths() throws Exception {
        ScriptedProvider provider = new ScriptedProvider();
        provider.enqueue("{\"propose\":false,\"reason\":\"duplicate\"}");
        List<SkillRule> active = List.of(new SkillRule("skill-active1", "regression→git",
                SkillKind.ROUTING, "LOGIC_ERROR", List.of("regression"),
                List.of("git_history"), "", SkillStatus.ACTIVE, "", "", "t0",
                List.of(), List.of(), "ds-1", 2, 0, "alice"));
        List<RejectedPathStore.RejectedPath> rejected = List.of(new RejectedPathStore.RejectedPath(
                "flaky timeout", "retry before history", "masked the commit correlation", "t0", "ds-0", 2));

        proposer(provider, ProposalBudget.defaults()).propose(
                TRACE, "", active, rejected, "flaky", "t1");

        String prompt = provider.requests.get(0).userPrompt();
        assertTrue(prompt.contains("regression→git"), prompt);
        assertTrue(prompt.contains("failed 2 times"), prompt);
        assertTrue(prompt.contains("masked the commit correlation"), prompt);
    }
}
