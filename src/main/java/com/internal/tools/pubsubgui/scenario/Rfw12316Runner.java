package com.internal.tools.pubsubgui.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internal.tools.pubsubgui.automation.model.CheckResult;
import com.internal.tools.pubsubgui.automation.model.Feasibility;
import com.internal.tools.pubsubgui.automation.model.RunSummary;
import com.internal.tools.pubsubgui.automation.model.ScenarioDef;
import com.internal.tools.pubsubgui.automation.model.ScenarioGroup;
import com.internal.tools.pubsubgui.automation.model.ScenarioResult;
import com.internal.tools.pubsubgui.config.MongoClientFactory;
import com.internal.tools.pubsubgui.model.PublishMessageRequest;
import com.internal.tools.pubsubgui.scenario.config.ScenarioProperties;
import com.internal.tools.pubsubgui.scenario.model.PhaseStatus;
import com.internal.tools.pubsubgui.scenario.model.RunPhase;
import com.internal.tools.pubsubgui.scenario.model.ScenarioRunState;
import com.internal.tools.pubsubgui.service.PubSubService;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * RFW-12316 item-ingestion run. Publishes the reserved TST messages to the Perf item topic, then the
 * enriched messages to the Creative Force topic, waits 10 seconds after each hop, and checks config
 * status plus runtime status.
 */
@Component
public class Rfw12316Runner {

    private static final Logger log = LoggerFactory.getLogger(Rfw12316Runner.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final String ROOT = "scenario-samples/catalog/rfw-12316/";
    private static final String CONFIG_DB = "item-config";
    private static final String RUNTIME_DB = "item-runtime";

    private static final List<String> PRODUCT_FLAGS = List.of(
            "isBundle", "isRental", "isCreaset", "isHem", "isHazardousItem",
            "isMonogramming", "isDropShippable", "showMyRegistry");
    private static final List<String> VARIANT_FLAGS = List.of("isOnlineExclusive", "isDefaultColor");
    private static final List<String> SKU_FLAGS = List.of(
            "isBigAndTall", "isClearance", "isDropShippable", "isTempMarkdown", "isSale", "isMerged");

    private final PubSubService pubSub;
    private final ScenarioProperties props;
    private final MongoClientFactory mongo;
    private final ObjectMapper mapper;
    private final ConfluenceResultsPublisher confluence;

    public Rfw12316Runner(PubSubService pubSub, ScenarioProperties props, MongoClientFactory mongo,
                          ObjectMapper mapper, ConfluenceResultsPublisher confluence) {
        this.pubSub = pubSub;
        this.props = props;
        this.mongo = mongo;
        this.mapper = mapper;
        this.confluence = confluence;
    }

    public void run(ScenarioRunState state) {
        Instant started = Instant.now();
        JsonNode manifest;
        try {
            manifest = mapper.readTree(read(ROOT + "manifest.json"));
        } catch (Exception e) {
            state.complete("ERROR", "Could not read the RFW-12316 manifest: " + e.getMessage());
            return;
        }
        String universeTopic = manifest.path("universeTopic").asText();
        String enrichedTopic = manifest.path("enrichedTopic").asText();
        int waitSeconds = manifest.path("waitSeconds").asInt(10);
        state.putInjection("universeTopic", universeTopic);
        state.putInjection("enrichedTopic", enrichedTopic);
        state.putInjection("waitSeconds", waitSeconds);
        state.putInjection("projectId", props.getProjectId());

        List<ScenarioResult> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int errored = 0;
        for (JsonNode caseNode : manifest.path("cases")) {
            String id = caseNode.path("id").asText();
            String title = caseNode.path("title").asText();
            RunPhase phase = state.addPhase(id + " " + title);
            state.startPhase(phase, "Publishing " + id);
            CheckResult result;
            try {
                publishAll(caseNode.path("universe"), universeTopic, waitSeconds, phase);
                publishAll(caseNode.path("enriched"), enrichedTopic, waitSeconds, phase);
                result = assertCase(state.getEnv(), caseNode);
            } catch (Exception e) {
                log.warn("rfw-12316 | {} failed | {}", id, e.getMessage());
                result = CheckResult.error(id, e.getMessage());
            }
            switch (result.status()) {
                case PASS -> passed++;
                case FAIL -> failed++;
                default -> errored++;
            }
            state.finishPhase(phase, result.status().name().equals("PASS") ? PhaseStatus.DONE : PhaseStatus.FAILED,
                    result.message());
            results.add(new ScenarioResult(definition(id, title, expectedText(caseNode)), result));
        }

        RunSummary summary = new RunSummary(
                state.getEnv(), null, results.size(),
                ISO.format(started), ISO.format(Instant.now()),
                Duration.between(started, Instant.now()).toMillis(),
                results.size(), passed, failed, 0, 0, errored, results);
        state.setVerify(summary);

        RunPhase publish = state.addPhase("Confluence");
        state.startPhase(publish, "Updating the RFW-12316 results page");
        String pageUrl = confluence.publish(summary);
        if (pageUrl == null) {
            state.putInjection("confluenceStatus", confluence.status());
            state.finishPhase(publish, PhaseStatus.SKIPPED, confluence.status());
        } else {
            state.putInjection("confluenceUrl", pageUrl);
            state.finishPhase(publish, PhaseStatus.DONE, pageUrl);
        }

        String overall = errored > 0 ? "ERROR" : failed > 0 ? "FAIL" : "PASS";
        String message = passed + " passed, " + failed + " failed, " + errored + " errored";
        if (pageUrl != null) {
            message = message + ". " + pageUrl;
        }
        state.complete(overall, message);
    }

    private void publishAll(JsonNode files, String topic, int waitSeconds, RunPhase phase) throws Exception {
        if (files == null || !files.isArray() || files.isEmpty()) {
            return;
        }
        for (JsonNode file : files) {
            String json = read(ROOT + file.asText());
            String messageId = pubSub.publish(props.getProjectId(), topic, new PublishMessageRequest(json, null, null));
            phase.setDetail("Published " + file.asText() + " id " + messageId);
        }
        sleep(waitSeconds);
        phase.setDetail("Waited " + waitSeconds + "s after " + topic);
    }

    private CheckResult assertCase(String env, JsonNode spec) {
        String id = spec.path("id").asText();
        if (spec.path("expectError").asBoolean(false)) {
            return assertRejected(env, id, spec.path("errorItemCode").asText());
        }
        List<String> problems = new ArrayList<>();
        String productId = spec.path("productId").asText();
        String variantId = spec.path("variantId").asText();
        String banner = spec.path("banner").asText();
        String configStatus = spec.path("configStatus").asText();
        String enrichment = spec.path("enrichmentStatus").asText();
        String runtime = spec.path("runtime").asText();

        MongoCollection<Document> configProducts = mongo.database(env, CONFIG_DB).getCollection("Product");
        MongoCollection<Document> configVariants = mongo.database(env, CONFIG_DB).getCollection("Variant");
        MongoCollection<Document> configSkus = mongo.database(env, CONFIG_DB).getCollection("SKU");
        MongoCollection<Document> runtimeProducts = mongo.database(env, RUNTIME_DB).getCollection("Product");
        MongoCollection<Document> runtimeVariants = mongo.database(env, RUNTIME_DB).getCollection("Variant");
        MongoCollection<Document> runtimeSkus = mongo.database(env, RUNTIME_DB).getCollection("SKU");

        Document product = configProducts.find(Filters.eq("_id", productId)).first();
        Document variant = configVariants.find(Filters.eq("_id", variantId)).first();
        checkConfig(problems, "Product", product, configStatus, enrichment, banner);
        checkConfig(problems, "Variant", variant, configStatus, enrichment, banner);

        Document runtimeProduct = runtimeProducts.find(Filters.eq("_id", productId)).first();
        Document runtimeVariant = runtimeVariants.find(Filters.eq("_id", variantId)).first();
        checkRuntime(problems, "Product", runtimeProduct, runtime, banner, PRODUCT_FLAGS);
        checkRuntime(problems, "Variant", runtimeVariant, runtime, banner, VARIANT_FLAGS);

        for (JsonNode skuSpec : spec.path("skus")) {
            String skuId = skuSpec.path("id").asText();
            Document sku = configSkus.find(Filters.eq("_id", skuId)).first();
            checkConfig(problems, "SKU " + skuId, sku, skuSpec.path("configStatus").asText(), enrichment, banner);
            if (sku != null) {
                Object merged = sku.get("isMerged");
                boolean expectedMerged = skuSpec.path("merged").asBoolean();
                if (!(merged instanceof Boolean) || ((Boolean) merged) != expectedMerged) {
                    problems.add("SKU " + skuId + " isMerged was " + merged + ", expected " + expectedMerged);
                }
            }
            Document runtimeSku = runtimeSkus.find(Filters.eq("_id", skuId)).first();
            checkRuntime(problems, "SKU " + skuId, runtimeSku, skuSpec.path("runtime").asText(), banner, SKU_FLAGS);
        }

        String expected = expectedText(spec);
        if (problems.isEmpty()) {
            return CheckResult.pass(id, 1, id + " matched. " + expected);
        }
        return CheckResult.fail(id, 1, problems.size(), String.join("; ", problems), expected,
                String.join("; ", problems), List.of(productId));
    }

    private CheckResult assertRejected(String env, String id, String itemCode) {
        MongoCollection<Document> skus = mongo.database(env, CONFIG_DB).getCollection("SKU");
        Document sku = skus.find(Filters.eq("_id", itemCode)).first();
        if (sku != null) {
            return CheckResult.fail(id, 1, 1, "Missing banner was stored as SKU " + itemCode,
                    "no SKU and one errors document", "SKU exists", List.of(itemCode));
        }
        boolean errorFound = false;
        for (Document error : mongo.database(env, CONFIG_DB).getCollection("errors").find().limit(40)) {
            if (error.toJson().contains(itemCode)) {
                errorFound = true;
                break;
            }
        }
        if (!errorFound) {
            return CheckResult.fail(id, 1, 1, "No errors document contains " + itemCode,
                    "schema rejection in errors", "errors document not found", List.of(itemCode));
        }
        return CheckResult.pass(id, 1, "Missing banner was rejected and " + itemCode + " was not stored");
    }

    private static void checkConfig(List<String> problems, String label, Document doc,
                                    String status, String enrichment, String banner) {
        if (doc == null) {
            problems.add(label + " was not found in item-config");
            return;
        }
        if (!status.equals(text(doc.get("status")))) {
            problems.add(label + " status was " + text(doc.get("status")) + ", expected " + status);
        }
        if (!enrichment.equals(text(doc.get("enrichmentStatus")))) {
            problems.add(label + " enrichmentStatus was " + text(doc.get("enrichmentStatus"))
                    + ", expected " + enrichment);
        }
        if (!banner.equals(text(doc.get("banner")))) {
            problems.add(label + " banner was " + text(doc.get("banner")) + ", expected " + banner);
        }
    }

    /**
     * {@code active} requires a runtime document with status active. {@code not-active} passes when the
     * document is absent (unpublished items are not promoted) or present with status inactive.
     */
    private static void checkRuntime(List<String> problems, String label, Document doc,
                                     String runtime, String banner, List<String> flags) {
        if ("active".equals(runtime)) {
            if (doc == null) {
                problems.add(label + " runtime document was not created");
                return;
            }
            if (!"active".equals(text(doc.get("status")))) {
                problems.add(label + " runtime status was " + text(doc.get("status")) + ", expected active");
            }
            if (!banner.equals(text(doc.get("banner")))) {
                problems.add(label + " runtime banner was " + text(doc.get("banner")) + ", expected " + banner);
            }
            checkFlags(problems, label, doc, flags);
            return;
        }
        if (doc == null) {
            return;
        }
        if ("active".equals(text(doc.get("status")))) {
            problems.add(label + " runtime status was active");
        }
        checkFlags(problems, label, doc, flags);
    }

    private static void checkFlags(List<String> problems, String label, Document doc, List<String> flags) {
        for (String flag : flags) {
            Object value = doc.get(flag);
            if (!(value instanceof Boolean)) {
                problems.add(label + " " + flag + " was " + value + ", expected true or false");
            }
        }
    }

    private static String expectedText(JsonNode spec) {
        if (spec.path("expectError").asBoolean(false)) {
            return "no stored SKU; one errors document for " + spec.path("errorItemCode").asText();
        }
        return "config status " + spec.path("configStatus").asText()
                + ", enrichment " + spec.path("enrichmentStatus").asText()
                + ", runtime " + spec.path("runtime").asText()
                + ", banner " + spec.path("banner").asText();
    }

    private static ScenarioDef definition(String id, String title, String expected) {
        return new ScenarioDef(id, ScenarioGroup.UNIVERSE_ITEM, "RFW-12316", title, "P1",
                Feasibility.READONLY, title, expected);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sleep(int seconds) throws InterruptedException {
        Thread.sleep(seconds * 1000L);
    }
}
