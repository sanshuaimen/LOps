package com.cifa.service;

import com.cifa.classification.ClassifiedFailure;
import com.cifa.classification.FailureCategory;
import com.cifa.classification.FailureClassifier;
import com.cifa.context.AnalysisContext;
import com.cifa.context.ContextBuilder;
import com.cifa.correlation.CorrelatedCommit;
import com.cifa.correlation.GitCorrelator;
import com.cifa.ingestion.LogNormalizer;
import com.cifa.parsing.FailureLogParser;
import com.cifa.parsing.ParsedFailure;
import com.cifa.parsing.StackFrame;
import com.cifa.parsing.SurefireReportParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DiagnosisTaskService {

    public PreparedDiagnosis prepare(DiagnosisRequest request) throws IOException, InterruptedException {
        String rawLog = Files.readString(request.logPath());
        String normalized = LogNormalizer.normalize(rawLog);
        Path repoRoot = request.repoRoot();

        ParsedFailure xmlFailure = SurefireReportParser.parse(repoRoot);
        boolean usedXmlReport = xmlFailure != null;
        ParsedFailure failure = usedXmlReport ? xmlFailure : FailureLogParser.parse(normalized);
        ClassifiedFailure classified = FailureClassifier.classify(failure);

        CorrelatedCommit commit = null;
        String sourceSnippet = "";
        List<String> correlationNotes = new ArrayList<>();
        boolean correlationAttempted = classified.category() != FailureCategory.INFRA_FLAKY;
        if (correlationAttempted) {
            StackFrame projectFrame = GitCorrelator.firstProjectFrame(failure.frames());
            if (projectFrame != null) {
                Optional<Path> filePath = GitCorrelator.resolveFilePathAcrossModules(repoRoot, projectFrame);
                if (filePath.isPresent()) {
                    commit = GitCorrelator.correlate(repoRoot, filePath.get(), projectFrame.lineNumber());
                    sourceSnippet = ContextBuilder.readSourceSnippet(filePath.get(), projectFrame.lineNumber());
                } else {
                    correlationNotes.add("source file could not be located for " + projectFrame.className());
                }
            }
        }

        AnalysisContext context = ContextBuilder.build(
                classified, commit, sourceSnippet, correlationNotes, request.contextBudget());
        return new PreparedDiagnosis(
                normalized,
                classified,
                commit,
                sourceSnippet,
                correlationNotes,
                context,
                usedXmlReport,
                correlationAttempted
        );
    }
}
