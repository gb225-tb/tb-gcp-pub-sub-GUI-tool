package com.internal.tools.pubsubgui.automation.ai;

import com.internal.tools.pubsubgui.automation.model.AiAnalyzeRequest;
import com.internal.tools.pubsubgui.automation.model.FieldDiff;

import java.util.List;

/** Builds the system + user prompt for the LLM providers from a failure payload. */
final class AiPromptBuilder {

    static final String SYSTEM =
            "You are a senior data-pipeline engineer helping validate the Tailored Brands Catalog "
            + "streaming ingestion (UniverseItem, EnrichedProduct, UniversePrice processors) after "
            + "go-live. You are given automated read-only check FAILURES run against a live MongoDB "
            + "(Firestore-compat) environment. For each failure, explain the most likely root cause, "
            + "the customer/data impact, and a concrete next step to investigate or fix. Be concise and "
            + "specific; reference the affected fields and document ids. Do not invent data.";

    private AiPromptBuilder() {
    }

    static String user(AiAnalyzeRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Environment: ").append(nz(request.env())).append('\n');
        sb.append("Failed scenarios: ").append(request.failures() == null ? 0 : request.failures().size())
                .append("\n\n");
        if (request.failures() != null) {
            int i = 1;
            for (AiAnalyzeRequest.FailedScenario f : request.failures()) {
                sb.append(i++).append(") [").append(nz(f.scenarioId())).append("] ")
                        .append(nz(f.title())).append(" (group=").append(nz(f.group()))
                        .append(", priority=").append(nz(f.priority())).append(")\n");
                sb.append("   status: ").append(nz(f.status())).append('\n');
                sb.append("   plan intent: ").append(nz(f.note())).append('\n');
                if (notBlank(f.message())) {
                    sb.append("   observed: ").append(f.message()).append('\n');
                }
                if (notBlank(f.expected())) {
                    sb.append("   expected: ").append(f.expected()).append('\n');
                }
                if (notBlank(f.actual())) {
                    sb.append("   actual: ").append(f.actual()).append('\n');
                }
                appendDiffs(sb, f.diffs());
                if (f.sampleIds() != null && !f.sampleIds().isEmpty()) {
                    sb.append("   sample ids: ").append(String.join(", ", f.sampleIds())).append('\n');
                }
                sb.append('\n');
            }
        }
        sb.append("Respond with a short section per scenario: Root cause, Impact, Next step.");
        return sb.toString();
    }

    private static void appendDiffs(StringBuilder sb, List<FieldDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return;
        }
        sb.append("   field diffs:\n");
        for (FieldDiff d : diffs) {
            if ("MATCH".equalsIgnoreCase(d.verdict())) {
                continue;
            }
            sb.append("     - ").append(nz(d.docType())).append('.').append(nz(d.field()))
                    .append(": expected=").append(nz(d.expected()))
                    .append(" actual=").append(nz(d.actual()))
                    .append(" [").append(nz(d.verdict())).append("]\n");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
