package com.internal.tools.pubsubgui.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internal.tools.pubsubgui.automation.AutomationEngine;
import com.internal.tools.pubsubgui.automation.model.CheckResult;
import com.internal.tools.pubsubgui.automation.model.Feasibility;
import com.internal.tools.pubsubgui.automation.model.RunRequest;
import com.internal.tools.pubsubgui.automation.model.RunSummary;
import com.internal.tools.pubsubgui.automation.model.ScenarioDef;
import com.internal.tools.pubsubgui.automation.model.ScenarioGroup;
import com.internal.tools.pubsubgui.automation.model.ScenarioResult;
import com.internal.tools.pubsubgui.config.MongoClientFactory;
import com.internal.tools.pubsubgui.model.PublishMessageRequest;
import com.internal.tools.pubsubgui.scenario.config.ScenarioProperties;
import com.internal.tools.pubsubgui.scenario.model.PhaseStatus;
import com.internal.tools.pubsubgui.scenario.model.RunPhase;
import com.internal.tools.pubsubgui.scenario.model.ScenarioKind;
import com.internal.tools.pubsubgui.scenario.model.ScenarioRunRequest;
import com.internal.tools.pubsubgui.scenario.model.ScenarioRunState;
import com.internal.tools.pubsubgui.scenario.model.ScenarioSpec;
import com.internal.tools.pubsubgui.scenario.model.VerifyMode;
import com.mongodb.client.model.Filters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Orchestrates a single Perf-only scenario run: inject a controlled input, wait, then re-use the
 * read-only validators to verify the outcome. Runs execute asynchronously on a small executor and
 * report progress through a live {@link ScenarioRunState} that the UI polls. Every write path
 * (Pub/Sub publish, GCS upload, GitHub dispatch) is Perf-guarded.
 */
@Service
public class ScenarioRunService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_RETAINED_RUNS = 50;

    private final ScenarioCatalog catalog;
    private final ScenarioProperties props;
    private final com.internal.tools.pubsubgui.service.PubSubService pubSub;
    private final GcsService gcs;
    private final GithubActionsService github;
    private final AutomationEngine engine;
    private final MongoClientFactory mongo;
    private final ObjectMapper mapper;

    private final Map<String, ScenarioRunState> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "scenario-runner");
        t.setDaemon(true);
        return t;
    });

    public ScenarioRunService(ScenarioCatalog catalog, ScenarioProperties props,
                              com.internal.tools.pubsubgui.service.PubSubService pubSub, GcsService gcs,
                              GithubActionsService github, AutomationEngine engine, MongoClientFactory mongo,
                              ObjectMapper mapper) {
        this.catalog = catalog;
        this.props = props;
        this.pubSub = pubSub;
        this.gcs = gcs;
        this.github = github;
        this.engine = engine;
        this.mongo = mongo;
        this.mapper = mapper;
    }

    /** Start a run; returns the initial state (already registered for polling). */
    public ScenarioRunState start(ScenarioRunRequest request) {
        String env = request == null ? null : request.env();
        if (!props.isAllowed(env)) {
            throw new IllegalArgumentException(
                    "Scenario injection is Perf-only. Environment '" + env + "' is not allowed.");
        }
        ScenarioSpec spec = catalog.find(request.scenarioId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + request.scenarioId()));
        if (!spec.enabled()) {
            throw new IllegalArgumentException("Scenario '" + spec.id() + "' is not enabled for injection.");
        }
        if (spec.kind() == ScenarioKind.BATCH
                && (request.version() == null || request.version().isBlank())) {
            throw new IllegalArgumentException("A version is required to dispatch the batch workflow.");
        }

        String runId = UUID.randomUUID().toString();
        ScenarioRunState state = new ScenarioRunState(runId, spec, env);
        register(runId, state);
        executor.submit(() -> {
            try {
                if (spec.kind() == ScenarioKind.STREAMING) {
                    runStreaming(spec, request, state);
                } else {
                    runBatch(spec, request, state);
                }
            } catch (RuntimeException e) {
                log.warn("scenario run | {} failed | {}", spec.id(), rootMessage(e));
                state.complete("ERROR", rootMessage(e));
            }
        });
        return state;
    }

    public ScenarioRunState get(String runId) {
        return runs.get(runId);
    }

    // ----------------------------------------------------------------- streaming

    private void runStreaming(ScenarioSpec spec, ScenarioRunRequest req, ScenarioRunState state) {
        String payload = firstNonBlank(req.payloadOverride(), readResource(spec.sampleResource()));
        state.putInjection("target", "topic " + spec.topicId());
        state.putInjection("projectId", props.getProjectId());

        RunPhase inject = state.addPhase("Injecting");
        state.startPhase(inject, "Publishing sample to " + spec.topicId());
        try {
            String messageId = pubSub.publish(props.getProjectId(), spec.topicId(),
                    new PublishMessageRequest(payload, null, null));
            state.putInjection("messageId", messageId);
            state.finishPhase(inject, PhaseStatus.DONE, "Published message id " + messageId);
        } catch (Exception e) {
            state.finishPhase(inject, PhaseStatus.FAILED, rootMessage(e));
            state.complete("ERROR", "Publish failed: " + rootMessage(e));
            return;
        }

        RunPhase wait = state.addPhase("Waiting");
        state.startPhase(wait, "Letting the streaming job consume (" + props.getStreamWaitSeconds() + "s)");
        sleep(props.getStreamWaitSeconds() * 1000L);
        state.finishPhase(wait, PhaseStatus.DONE, null);

        verifyAndComplete(spec, payload, state);
    }

    // -------------------------------------------------------------------- batch

    private void runBatch(ScenarioSpec spec, ScenarioRunRequest req, ScenarioRunState state) {
        byte[] content = decodeOrSample(req.fileBase64(), spec.sampleResource());
        String fileName = firstNonBlank(req.fileName(), spec.defaultFileName());
        String stamped = stampFileName(fileName);
        String objectName = spec.gcsObjectPrefix() + stamped;

        RunPhase upload = state.addPhase("Uploading");
        state.startPhase(upload, "Uploading to gs://" + spec.gcsBucket() + "/" + objectName);
        String gsUri;
        try {
            gsUri = gcs.upload(state.getEnv(), spec.gcsBucket(), objectName, content, contentType(fileName));
            state.putInjection("gcsUri", gsUri);
            state.finishPhase(upload, PhaseStatus.DONE, gsUri);
        } catch (Exception e) {
            state.finishPhase(upload, PhaseStatus.FAILED, rootMessage(e));
            state.complete("ERROR", "GCS upload failed: " + rootMessage(e));
            return;
        }

        RunPhase trigger = state.addPhase("Triggering");
        Long runIdGh = null;
        String repo = spec.githubRepo();
        if (github.isConfigured()) {
            state.startPhase(trigger, "workflow_dispatch " + spec.workflowFile() + " (perf, " + spec.processor() + ")");
            try {
                Map<String, String> inputs = Map.of(
                        "environment", "perf",
                        "processor", spec.processor(),
                        "version", req.version());
                github.dispatch(repo, spec.workflowFile(), null, inputs);
                state.finishPhase(trigger, PhaseStatus.DONE, "Dispatched " + spec.workflowFile());
                RunPhase poll = state.addPhase("Polling");
                runIdGh = pollRun(repo, spec.workflowFile(), state, poll);
            } catch (Exception e) {
                state.finishPhase(trigger, PhaseStatus.FAILED, rootMessage(e));
                // File is uploaded; continue to verify (a scheduled run may still consume it).
            }
        } else {
            state.finishPhase(trigger, PhaseStatus.SKIPPED,
                    "GitHub token not configured — file uploaded; batch run not dispatched.");
            RunPhase wait = state.addPhase("Waiting");
            state.startPhase(wait, "Waiting " + props.getStreamWaitSeconds() + "s before verify");
            sleep(props.getStreamWaitSeconds() * 1000L);
            state.finishPhase(wait, PhaseStatus.DONE, null);
        }
        state.putInjection("githubRunId", runIdGh);

        String payload = new String(content, StandardCharsets.UTF_8);
        verifyAndComplete(spec, payload, state);
    }

    /** Poll the dispatched GitHub run until completion or timeout; records url/conclusion. */
    private Long pollRun(String repo, String workflowFile, ScenarioRunState state, RunPhase poll) {
        state.startPhase(poll, "Locating dispatched run…");
        long deadline = System.currentTimeMillis() + props.getBatchTimeoutSeconds() * 1000L;
        long pollMs = Math.max(5, props.getBatchPollSeconds()) * 1000L;
        GithubActionsService.RunInfo run = null;
        // Give GitHub a moment to register the run before the first lookup.
        sleep(Math.min(pollMs, 8000L));
        while (System.currentTimeMillis() < deadline) {
            try {
                GithubActionsService.RunInfo latest = run == null
                        ? github.findLatestRun(repo, workflowFile, null)
                        : github.getRun(repo, run.id());
                if (latest != null) {
                    run = latest;
                    state.putInjection("githubRunUrl", run.htmlUrl());
                    state.startPhase(poll, "Run " + run.id() + " status=" + run.status()
                            + (run.conclusion() != null ? " conclusion=" + run.conclusion() : ""));
                    if ("completed".equalsIgnoreCase(run.status())) {
                        boolean ok = "success".equalsIgnoreCase(run.conclusion());
                        state.finishPhase(poll, ok ? PhaseStatus.DONE : PhaseStatus.FAILED,
                                "Run " + run.conclusion() + " — " + run.htmlUrl());
                        return run.id();
                    }
                }
            } catch (RuntimeException e) {
                state.startPhase(poll, "Poll error: " + rootMessage(e));
            }
            sleep(pollMs);
        }
        state.finishPhase(poll, PhaseStatus.FAILED,
                "Timed out after " + props.getBatchTimeoutSeconds() + "s waiting for the run.");
        return run == null ? null : run.id();
    }

    // ------------------------------------------------------------------- verify

    private void verifyAndComplete(ScenarioSpec spec, String payload, ScenarioRunState state) {
        RunPhase verify = state.addPhase("Verifying");
        state.startPhase(verify, "Running read-only validators");
        RunSummary summary;
        try {
            summary = spec.verifyMode() == VerifyMode.INVENTORY_PRESENCE
                    ? verifyInventory(spec, payload, state)
                    : verifyCatalog(spec, payload, state);
        } catch (RuntimeException e) {
            state.finishPhase(verify, PhaseStatus.FAILED, rootMessage(e));
            state.complete("ERROR", "Verify failed: " + rootMessage(e));
            return;
        }
        state.setVerify(summary);

        String overall;
        String message;
        if (summary.errored() > 0) {
            overall = "ERROR";
            message = summary.errored() + " check(s) errored";
        } else if (summary.failed() > 0) {
            overall = "FAIL";
            message = summary.failed() + " of " + summary.total() + " check(s) failed";
        } else if (summary.passed() > 0) {
            overall = "PASS";
            message = summary.passed() + " check(s) passed";
        } else {
            overall = "FAIL";
            message = "Injected data was not found within the wait window";
        }
        state.finishPhase(verify, overall.equals("PASS") ? PhaseStatus.DONE : PhaseStatus.FAILED, message);
        RunPhase done = state.addPhase("Done");
        state.finishPhase(done, PhaseStatus.DONE, message);
        state.complete(overall, message);
    }

    private RunSummary verifyCatalog(ScenarioSpec spec, String payload, ScenarioRunState state) {
        String productId = deriveProductId(spec, payload);
        if (productId != null) {
            state.putInjection("productId", productId);
        }
        RunRequest req = new RunRequest(state.getEnv(), spec.verifyGroup(), null, true, productId, null);
        return engine.run(req);
    }

    /** Read-only presence check of the injected ItemId in the inventory Mongo collections. */
    private RunSummary verifyInventory(ScenarioSpec spec, String payload, ScenarioRunState state) {
        String itemId = deriveItemId(spec, payload);
        String db = spec.verifyItemDb();
        boolean runtime = "inventory-runtime".equalsIgnoreCase(db);
        String collection = runtime ? "Inventory" : "Item";
        Instant start = Instant.now();

        CheckResult result;
        if (itemId == null || itemId.isBlank()) {
            result = CheckResult.skip(spec.id(), "Could not derive an ItemId from the injected sample.");
        } else {
            state.putInjection("itemId", itemId);
            long count;
            if (runtime) {
                // runtime Inventory docs are keyed <sku>_<locationId>; match the sku prefix.
                count = mongo.database(state.getEnv(), db).getCollection(collection)
                        .countDocuments(Filters.regex("_id", "^" + Pattern.quote(itemId)));
            } else {
                // inventory-config Item docs are keyed by sku.
                count = mongo.database(state.getEnv(), db).getCollection(collection)
                        .countDocuments(Filters.eq("_id", itemId));
            }
            if (count > 0) {
                result = CheckResult.pass(spec.id(), (int) count,
                        "Found " + count + " " + collection + " document(s) for " + itemId + ".");
            } else {
                result = CheckResult.fail(spec.id(), 0, 1,
                        "No " + collection + " document found for " + itemId + " within the wait window.",
                        "At least one " + collection + " doc for " + itemId,
                        "none found", List.of(itemId));
            }
        }

        ScenarioDef def = new ScenarioDef(spec.id(), ScenarioGroup.CROSS_PROCESSOR, spec.category().label(),
                spec.shortName() + " presence", "P1", Feasibility.READONLY,
                "Presence check of the injected ItemId in " + db + "." + collection, spec.description());
        Instant end = Instant.now();
        int p = result.status().name().equals("PASS") ? 1 : 0;
        int f = result.status().name().equals("FAIL") ? 1 : 0;
        int s = result.status().name().equals("SKIP") ? 1 : 0;
        return new RunSummary(state.getEnv(), itemId, 1,
                DateTimeFormatter.ISO_INSTANT.format(start), DateTimeFormatter.ISO_INSTANT.format(end),
                Duration.between(start, end).toMillis(), 1, p, f, s, 0, 0,
                List.of(new ScenarioResult(def, result)));
    }

    // ------------------------------------------------------------------- derive

    private String deriveProductId(ScenarioSpec spec, String payload) {
        JsonNode node = tryParse(payload);
        if (node == null) {
            return null;
        }
        if ("ENRICHED".equals(spec.verifyGroup())) {
            return text(node, "productId");
        }
        if ("UNIVERSE_ITEM".equals(spec.verifyGroup())) {
            // Only derivable from the streaming JSON message (batch feed is a CSV/TXT file).
            String parent = text(node, "ParentProductCode");
            String color = text(node, "ProductColorCode");
            if (parent != null && color != null) {
                return parent + "_" + color;
            }
        }
        return null; // PRICE (and batch feeds) verify across the sample without a productId scope.
    }

    private String deriveItemId(ScenarioSpec spec, String payload) {
        if (spec.kind() == ScenarioKind.STREAMING) {
            JsonNode node = tryParse(payload);
            return node == null ? null : text(node, "ItemId");
        }
        // Batch: first non-header data row's first column (ITEMID).
        for (String line : payload.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.toUpperCase().startsWith("ITEMID")) {
                continue;
            }
            String[] cols = trimmed.split(",");
            return cols.length > 0 ? cols[0].trim() : null;
        }
        return null;
    }

    // ------------------------------------------------------------------- helpers

    private void register(String runId, ScenarioRunState state) {
        runs.put(runId, state);
        if (runs.size() > MAX_RETAINED_RUNS) {
            runs.entrySet().stream()
                    .filter(e -> e.getValue().isDone())
                    .findFirst()
                    .ifPresent(e -> runs.remove(e.getKey()));
        }
    }

    private JsonNode tryParse(String payload) {
        try {
            return payload == null ? null : mapper.readTree(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private byte[] decodeOrSample(String base64, String resource) {
        if (base64 != null && !base64.isBlank()) {
            try {
                return Base64.getDecoder().decode(base64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Uploaded file is not valid base64: " + e.getMessage());
            }
        }
        return readResource(resource).getBytes(StandardCharsets.UTF_8);
    }

    private String readResource(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Missing bundled sample: " + resource, e);
        }
    }

    private String stampFileName(String fileName) {
        String stamp = STAMP.format(Instant.now().atZone(java.time.ZoneOffset.UTC));
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return fileName + "-" + stamp;
        }
        return fileName.substring(0, dot) + "-" + stamp + fileName.substring(dot);
    }

    private static String contentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        return "text/plain";
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null || msg.isBlank() ? cur.getClass().getSimpleName() : msg;
    }
}
