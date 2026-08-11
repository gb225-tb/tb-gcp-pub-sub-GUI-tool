package com.internal.tools.pubsubgui.automation.model;

import java.util.List;

/**
 * Payload the UI posts to ask for an AI explanation of one or more failed scenarios. Only
 * non-sensitive check context is sent (never connection strings / secrets).
 */
public record AiAnalyzeRequest(String env, List<FailedScenario> failures) {

    /** A single failed scenario's context for analysis. */
    public record FailedScenario(
            String scenarioId,
            String group,
            String title,
            String priority,
            String note,
            String status,
            String message,
            String expected,
            String actual,
            List<String> sampleIds,
            List<FieldDiff> diffs) {
    }
}
