package com.internal.tools.pubsubgui.automation.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of running one scenario's validator. Carries enough context for the UI to render an
 * expected-vs-actual explanation and for the AI analyzer to reason about the failure.
 *
 * @param scenarioId  the {@link ScenarioDef#id()} this result is for
 * @param status      terminal status
 * @param checked     number of documents inspected
 * @param failed      number of documents that violated the invariant
 * @param message     one-line human summary
 * @param expected    what the invariant requires
 * @param actual      what was observed (for failures)
 * @param sampleIds   offending document {@code _id}s (capped)
 * @param diffs       per-field diffs (only populated for HCL cross-verification)
 */
public record CheckResult(
        String scenarioId,
        CheckStatus status,
        int checked,
        int failed,
        String message,
        String expected,
        String actual,
        List<String> sampleIds,
        List<FieldDiff> diffs) {

    public static CheckResult pass(String scenarioId, int checked, String message) {
        return new CheckResult(scenarioId, CheckStatus.PASS, checked, 0, message, null, null,
                List.of(), List.of());
    }

    public static CheckResult fail(String scenarioId, int checked, int failed, String message,
                                   String expected, String actual, List<String> sampleIds) {
        return new CheckResult(scenarioId, CheckStatus.FAIL, checked, failed, message, expected, actual,
                sampleIds == null ? List.of() : sampleIds, List.of());
    }

    public static CheckResult skip(String scenarioId, String message) {
        return new CheckResult(scenarioId, CheckStatus.SKIP, 0, 0, message, null, null,
                List.of(), List.of());
    }

    public static CheckResult na(String scenarioId, String message) {
        return new CheckResult(scenarioId, CheckStatus.NA, 0, 0, message, null, null,
                List.of(), List.of());
    }

    public static CheckResult error(String scenarioId, String message) {
        return new CheckResult(scenarioId, CheckStatus.ERROR, 0, 0, message, null, null,
                List.of(), List.of());
    }

    public static CheckResult diff(String scenarioId, int checked, List<FieldDiff> diffs, String message) {
        int differs = 0;
        List<FieldDiff> safe = diffs == null ? new ArrayList<>() : diffs;
        for (FieldDiff d : safe) {
            if (!"MATCH".equalsIgnoreCase(d.verdict())) {
                differs++;
            }
        }
        CheckStatus status = differs == 0 ? CheckStatus.PASS : CheckStatus.FAIL;
        return new CheckResult(scenarioId, status, checked, differs, message, null, null, List.of(), safe);
    }
}
