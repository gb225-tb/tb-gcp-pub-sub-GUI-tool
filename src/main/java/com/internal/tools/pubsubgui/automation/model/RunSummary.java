package com.internal.tools.pubsubgui.automation.model;

import java.util.List;

/**
 * Aggregate result of a run: the environment, when it ran, roll-up counts by status, and the
 * per-scenario results.
 */
public record RunSummary(
        String env,
        String productId,
        int sampleSize,
        String startedAt,
        String finishedAt,
        long durationMs,
        int total,
        int passed,
        int failed,
        int skipped,
        int notApplicable,
        int errored,
        List<ScenarioResult> results) {
}
