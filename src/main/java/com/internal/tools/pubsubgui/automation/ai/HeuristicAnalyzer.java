package com.internal.tools.pubsubgui.automation.ai;

import com.internal.tools.pubsubgui.automation.model.AiAnalyzeRequest;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeResponse;
import com.internal.tools.pubsubgui.automation.model.FieldDiff;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Offline, self-contained failure analyzer — no network, no credentials. For every failed scenario it
 * explains, in plain language: what invariant broke, the most likely root cause (specific to that check
 * or its processor group), what it impacts, and exactly where to look next. When field-level diffs are
 * present (the HCL-vs-streaming checks) it reads the diverging fields and tailors the explanation to
 * them. This is the default analyzer and the fallback whenever no LLM provider is wired.
 */
final class HeuristicAnalyzer implements AiAnalyzer {

    /** Per-check knowledge: root cause + suggested fix, keyed by scenario id. */
    private record Insight(String rootCause, String fix) {
    }

    private static final Map<String, Insight> SCENARIO = new LinkedHashMap<>();
    private static final Map<String, Insight> GROUP = new LinkedHashMap<>();
    /** Field -> short note on what drives that field, used when diffs are present. */
    private static final Map<String, String> FIELD_HINT = new LinkedHashMap<>();

    static {
        // ── UniverseItem (Product / Variant / SKU) ──────────────────────────────
        SCENARIO.put("UI-05", new Insight(
                "Variant.colorCode was not left zero-padded to colorCodePadWidth (e.g. numeric '5' should become '05'). "
                        + "The padding transform was skipped or the pad width is misconfigured.",
                "Verify the colorCode padding step and colorCodePadWidth; confirm ProductColorCode is numeric. "
                        + "Note Variant._id intentionally uses the RAW color, only colorCode is padded."));
        SCENARIO.put("UI-06", new Insight(
                "SKU.sizeCode is missing the '<division>_' prefix — Division was blank/late at SKU build time, or the "
                        + "prefixing step did not run.",
                "Ensure Division is fanned onto the SKU before sizeCode is composed as '<division>_<rawSizeCode>'."));
        SCENARIO.put("UI-07", new Insight(
                "SKU.division does not mirror Product.division — the Product→SKU division fan-out (needed for threshold "
                        + "gating) did not happen.",
                "Check the division fan-out in the SKU writer; confirm Product.division is set when the SKU is built."));
        SCENARIO.put("UI-10", new Insight(
                "Text attributes (fit / material / color / colorFamily) were not INITCAP-normalized — the casing "
                        + "transform was skipped, so screaming-caps or raw source casing leaked through.",
                "Verify the INITCAP transform runs on those attributes on the Product/Variant builders."));

        // ── EnrichedProduct ─────────────────────────────────────────────────────
        SCENARIO.put("EN-01", new Insight(
                "EnrichedProduct._id != variantId, or productId != variantId stripped after the last '_' — the id "
                        + "derivation drifted.",
                "Re-check enriched id derivation: _id/variantId = raw productId; productId = substring before last '_'."));
        SCENARIO.put("EN-02", new Insight(
                "publishedAt was NOT stamped even though a mainImage is present — the publish-readiness gate did not fire "
                        + "(or publishedAt set-once was cleared).",
                "Check the publish gate: mainImage presence must stamp publishedAt; confirm set-once isn't wiped on re-ingest."));
        SCENARIO.put("EN-03", new Insight(
                "publishedAt was set despite NO mainImage — the gate published prematurely.",
                "Ensure absence of mainImage blocks publishedAt (schema passes but publish must fail)."));
        SCENARIO.put("EN-05", new Insight(
                "A published product is missing seoUrl — generation did not run, or seoUrl was cleared on a later re-ingest.",
                "Check seoUrl generation (brand + shortDescription + productId) and that it is merge-preserved on replace."));

        // ── Price ───────────────────────────────────────────────────────────────
        SCENARIO.put("PR-03", new Insight(
                "Price._id/sku is not uppercased+trimmed from CatentryPartNumber — normalization was skipped.",
                "Apply uppercase(trim(CatentryPartNumber)) when deriving Price._id."));
        SCENARIO.put("PR-04", new Insight(
                "Price.productId/variantId do not equal the linked SKU's ids — enrichment used stale/absent SKU ids, or the "
                        + "SKU moved parent without the Price being repointed.",
                "Confirm the SKU exists before Price enrichment and re-run the PriceCatalogId backfill (Wait.on skusDone)."));
        SCENARIO.put("PR-09", new Insight(
                "SKU.isSale is inconsistent with salePrice < listPrice — isSale was not recomputed after a price change, or "
                        + "list/sale routing wrote the wrong field.",
                "Recompute isSale (salePrice < listPrice) in SkuAttributesFirestoreWriter on every price update."));
        SCENARIO.put("PR-TYPE", new Insight(
                "listPrice/salePrice/promoPrice are stored as strings instead of numbers — monetary type coercion is missing "
                        + "on ingest (the classic 'integer/number found where string expected' consumer-reject class).",
                "Coerce monetary fields to numeric before write so downstream consumers don't reject them."));

        // ── Cross-processor ───────────────────────────────────────────────────────
        SCENARIO.put("CP-08", new Insight(
                "A SKU/Variant references a Product/Variant document that does not exist — an orphan from out-of-order writes, "
                        + "or a re-key/merge that didn't repoint its children.",
                "Check write ordering and the merge/re-key path repointing (children must follow the surviving parent id)."));
        SCENARIO.put("ERR-01", new Insight(
                "Messages are sitting in the errors collection (schema validation / processing failures). After a clean "
                        + "go-live this should be 0.",
                "Group the errors collection by errorType, inspect rawMessage for the top offenders, fix the feed and replay."));

        // ── Group-level fallback (any id not listed above) ──────────────────────
        GROUP.put("UNIVERSE_ITEM", new Insight(
                "A UniverseItem transformation/identity invariant did not hold for the sampled Product/Variant/SKU docs.",
                "Inspect the sampled ids in Compare; check the Product/Variant/SKU builders for the flagged transform."));
        GROUP.put("ENRICHED", new Insight(
                "An EnrichedProduct rule (identity, publish gate, or seoUrl) did not hold.",
                "Check the enriched build + publish-readiness logic and merge-preserve on re-ingest."));
        GROUP.put("PRICE", new Insight(
                "A Price rule (id normalization, catalog-id enrichment, isSale, or numeric types) did not hold.",
                "Inspect the Price docs for the sampled skus and the price writer/backfill path."));
        GROUP.put("CROSS_PROCESSOR", new Insight(
                "A cross-collection consistency / merge-preserve invariant did not hold across processors.",
                "Check that ids resolve across collections and that foreign fields survive re-ingest (no clobber)."));
        GROUP.put("HCL_XFORM", new Insight(
                "The streaming pipeline and the HCL migration diverge for this product (fields or counts). Some divergence "
                        + "is intentional (identity schemes differ) — confirm against the HCL_vs_Streaming_Diffs matrix.",
                "Open the HCL and Commerce Tool explorers for this productId and compare the flagged fields/counts."));

        // ── Field-level hints (used when diffs are present) ─────────────────────
        FIELD_HINT.put("colorcode", "colorCode padding vs raw-color id — commonly an intentional identity-scheme difference.");
        FIELD_HINT.put("sizecode", "the '<division>_' prefix presence differs (division timing).");
        FIELD_HINT.put("division", "division fan-out timing / threshold gating.");
        FIELD_HINT.put("seourl", "seoUrl generation or merge-preserve on re-ingest.");
        FIELD_HINT.put("publishedat", "publish gate (mainImage) and set-once timing.");
        FIELD_HINT.put("issale", "isSale must be recomputed when salePrice < listPrice.");
        FIELD_HINT.put("productid", "id derivation / catalog-id enrichment.");
        FIELD_HINT.put("variantid", "id derivation / catalog-id enrichment.");
        FIELD_HINT.put("status", "active-state rollup cascade (all SKUs inactive → Variant/Product inactive).");
        FIELD_HINT.put("mainimage", "image extraction feeds the publish gate.");
    }

    @Override
    public AiAnalyzeResponse analyze(AiAnalyzeRequest request) {
        List<AiAnalyzeRequest.FailedScenario> failures =
                request.failures() == null ? List.of() : request.failures();
        if (failures.isEmpty()) {
            return new AiAnalyzeResponse("heuristic", false, "No failures to analyze.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(overview(request.env(), failures)).append("\n\n");

        for (AiAnalyzeRequest.FailedScenario f : failures) {
            Insight insight = insightFor(f);
            sb.append("### [").append(nz(f.scenarioId())).append("] ").append(nz(f.title()));
            if (notBlank(f.priority())) {
                sb.append("  (").append(f.priority()).append(')');
            }
            sb.append('\n');
            if (notBlank(f.note())) {
                sb.append("- What it checks: ").append(f.note()).append('\n');
            }
            if (notBlank(f.message())) {
                sb.append("- Result: ").append(f.message()).append('\n');
            }
            if (notBlank(f.expected())) {
                sb.append("- Expected: ").append(f.expected()).append('\n');
            }
            if (notBlank(f.actual())) {
                sb.append("- Observed: ").append(f.actual()).append('\n');
            }
            sb.append("- Likely root cause: ").append(insight.rootCause()).append('\n');
            String diffInsight = diffInsight(f.diffs());
            if (notBlank(diffInsight)) {
                sb.append("- Field signals: ").append(diffInsight).append('\n');
            }
            sb.append("- Impact: ").append(impact(f)).append('\n');
            sb.append("- Suggested fix: ").append(insight.fix()).append('\n');
            sb.append("- Where to look: ").append(whereToLook(f)).append('\n');
            appendDiffTable(sb, f.diffs());
            if (f.sampleIds() != null && !f.sampleIds().isEmpty()) {
                sb.append("- Sample ids: ").append(String.join(", ", f.sampleIds())).append('\n');
            }
            sb.append('\n');
        }
        return new AiAnalyzeResponse("heuristic", false, sb.toString().trim());
    }

    /** Roll-up header: how many failures, by group, and the most frequent diverging field. */
    private static String overview(String env, List<AiAnalyzeRequest.FailedScenario> failures) {
        Map<String, Integer> byGroup = new TreeMap<>();
        Map<String, Integer> fieldFreq = new LinkedHashMap<>();
        int p1 = 0;
        for (AiAnalyzeRequest.FailedScenario f : failures) {
            byGroup.merge(nz(f.group()).isBlank() ? "OTHER" : f.group(), 1, Integer::sum);
            if ("P1".equalsIgnoreCase(nz(f.priority()))) {
                p1++;
            }
            if (f.diffs() != null) {
                for (FieldDiff d : f.diffs()) {
                    if (!"MATCH".equalsIgnoreCase(nz(d.verdict())) && notBlank(d.field())) {
                        fieldFreq.merge(d.docType() + "." + d.field(), 1, Integer::sum);
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Offline rule-based analysis for **").append(nz(env)).append("** — ")
                .append(failures.size()).append(" failing scenario(s)");
        if (p1 > 0) {
            sb.append(", ").append(p1).append(" of them P1");
        }
        sb.append(".\n");
        sb.append("By group: ");
        boolean first = true;
        for (Map.Entry<String, Integer> e : byGroup.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append("×").append(e.getValue());
            first = false;
        }
        String topField = fieldFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        if (topField != null) {
            sb.append("\nMost frequent diverging field: ").append(topField).append('.');
        }
        return sb.toString();
    }

    private static Insight insightFor(AiAnalyzeRequest.FailedScenario f) {
        String id = nz(f.scenarioId());
        Insight direct = SCENARIO.get(id);
        if (direct != null) {
            return direct;
        }
        // XF-* share the HCL cross-verify group behavior.
        if (id.startsWith("XF-")) {
            return GROUP.get("HCL_XFORM");
        }
        Insight byGroup = GROUP.get(nz(f.group()).toUpperCase());
        if (byGroup != null) {
            return byGroup;
        }
        return new Insight(
                "An invariant from the test plan did not hold for the sampled documents.",
                "Pull the sampled ids in the Compare view and inspect the fields named in the result message.");
    }

    /** Reads the diverging fields and turns them into a short, field-specific hint. */
    private static String diffInsight(List<FieldDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return "";
        }
        int gaps = 0;
        int differs = 0;
        StringBuilder hints = new StringBuilder();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (FieldDiff d : diffs) {
            String verdict = nz(d.verdict()).toUpperCase();
            if (verdict.equals("MATCH")) {
                continue;
            }
            if (verdict.equals("GAP")) {
                gaps++;
            } else {
                differs++;
            }
            String hint = FIELD_HINT.get(nz(d.field()).toLowerCase());
            if (hint != null && seen.add(hint)) {
                if (hints.length() > 0) {
                    hints.append(' ');
                }
                hints.append(nz(d.field())).append(": ").append(hint);
            }
        }
        if (gaps == 0 && differs == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (differs > 0) {
            sb.append(differs).append(" field(s) hold different values");
        }
        if (gaps > 0) {
            sb.append(differs > 0 ? "; " : "").append(gaps).append(" field(s) present on only one side (GAP)");
        }
        sb.append('.');
        if (hints.length() > 0) {
            sb.append(' ').append(hints);
        }
        return sb.toString();
    }

    private static String impact(AiAnalyzeRequest.FailedScenario f) {
        String id = nz(f.scenarioId());
        if (id.equals("ERR-01")) {
            return "Products/prices in the failed messages are missing or stale downstream (CT / runtime).";
        }
        if (id.startsWith("XF-")) {
            return "Possible display/matching differences between HCL and streaming for the same merchandise.";
        }
        return "P1".equalsIgnoreCase(nz(f.priority()))
                ? "High — affects identity, data-loss, or publish/pricing correctness."
                : "Medium — edge/derivation correctness.";
    }

    private static String whereToLook(AiAnalyzeRequest.FailedScenario f) {
        String id = nz(f.scenarioId());
        if (id.equals("ERR-01")) {
            return "The errors collection (group by errorType).";
        }
        if (id.startsWith("XF-")) {
            return "The HCL and Commerce Tool explorers for this productId; compare the flagged fields/counts.";
        }
        return "The listed sample _ids in the Compare view — inspect the fields named above.";
    }

    private static void appendDiffTable(StringBuilder sb, List<FieldDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return;
        }
        boolean any = false;
        for (FieldDiff d : diffs) {
            if ("MATCH".equalsIgnoreCase(nz(d.verdict()))) {
                continue;
            }
            if (!any) {
                sb.append("- Field diffs:\n");
                any = true;
            }
            sb.append("    - ").append(nz(d.docType())).append('.').append(nz(d.field()))
                    .append(": HCL=").append(nz(d.expected())).append(" | streaming=")
                    .append(nz(d.actual())).append(" [").append(nz(d.verdict())).append("]\n");
        }
    }

    private static boolean notBlank(String s) {
        return Objects.nonNull(s) && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
