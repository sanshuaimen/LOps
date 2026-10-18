package com.cifa.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mutable per-investigation bookkeeping: rounds, tokens, tool-call history,
 * consecutive failures and repeated-call-without-progress detection. The
 * agent loop reads this to decide when to stop; the model never sees it.
 */
public final class InvestigationState {

    public record ToolCallRecord(int round, String toolName, String argumentsSignature,
                                 String status, int evidenceCountAfter, String hypothesis) {
    }

    private final String taskId;
    private final long startedAtNanos = System.nanoTime();
    private final List<ToolCallRecord> toolCalls = new ArrayList<>();
    private final java.util.Set<String> seenSignatures = new java.util.HashSet<>();
    private int round = 1;
    private int tokensUsed;
    private int consecutiveToolFailures;
    private int repeatsWithoutProgress;
    private AgentStopReason stopReason;

    public InvestigationState(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("task id is required");
        }
        this.taskId = taskId;
    }

    public String taskId() {
        return taskId;
    }

    public int round() {
        return round;
    }

    public void nextRound() {
        round++;
    }

    public int tokensUsed() {
        return tokensUsed;
    }

    public void recordTokens(int tokens) {
        if (tokens > 0) {
            tokensUsed += tokens;
        }
    }

    public int consecutiveToolFailures() {
        return consecutiveToolFailures;
    }

    public int repeatsWithoutProgress() {
        return repeatsWithoutProgress;
    }

    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    public List<ToolCallRecord> toolCalls() {
        return List.copyOf(toolCalls);
    }

    /**
     * Records one tool outcome. Tools are read-only and deterministic, so a
     * repeated tool+arguments signature can never return new information —
     * that is what counts as no-progress repetition, regardless of how many
     * evidence entries pile up.
     *
     * @return true when this call repeated a previous call verbatim
     */
    public boolean recordToolCall(int round, String toolName, Map<String, Object> arguments,
                                  String status, int evidenceCountAfter, String hypothesis) {
        String signature = toolName + "(" + signatureOf(arguments) + ")";
        toolCalls.add(new ToolCallRecord(round, toolName, signature, status, evidenceCountAfter, hypothesis));
        boolean repeatedWithoutProgress = !seenSignatures.add(signature);
        if (repeatedWithoutProgress) {
            repeatsWithoutProgress++;
        }
        return repeatedWithoutProgress;
    }

    public void recordToolOutcome(String status) {
        boolean failure = status.equals("INVALID_ARGUMENT") || status.equals("FAILED") || status.equals("TIMEOUT");
        if (failure) {
            consecutiveToolFailures++;
        } else {
            consecutiveToolFailures = 0;
        }
    }

    public void stop(AgentStopReason reason) {
        if (stopReason == null) {
            stopReason = reason;
        }
    }

    public AgentStopReason stopReason() {
        return stopReason;
    }

    static String signatureOf(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        arguments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parts.add(entry.getKey() + "=" + entry.getValue()));
        return String.join(",", parts);
    }
}
