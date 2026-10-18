package com.cifa.service;

import com.cifa.context.ContextBudget;

import java.nio.file.Path;

public record DiagnosisRequest(Path logPath, Path repoRoot, ContextBudget contextBudget) {
    public DiagnosisRequest {
        if (logPath == null || repoRoot == null) {
            throw new IllegalArgumentException("logPath and repoRoot are required");
        }
        contextBudget = contextBudget == null ? ContextBudget.DEFAULT : contextBudget;
    }
}
