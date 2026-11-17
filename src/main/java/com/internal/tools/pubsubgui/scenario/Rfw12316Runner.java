package com.internal.tools.pubsubgui.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internal.tools.pubsubgui.automation.model.CheckResult;
import com.internal.tools.pubsubgui.automation.model.CheckStatus;
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
import com.internal.tools.pubsubgui.scenario.model.ScenarioRunRequest;
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
 * RFW-12316 staged run. Each stage publishes to the universe, price, promo, or Creative Force topic,
 * waits until item-config matches, then checks item-runtime. Later stages run only after the previous
 * stage's documents exist, because enrichment and price updates are lost when the SKU is not there yet.
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
            "isBigAndTall", "isClearance", "isDropShippable", "isSale", "isMerged");

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

    public void run(ScenarioRunState state, ScenarioRunRequest request) {
        Instant started = Instant.now();
        JsonNode manifest;
        try {
            manifest = mapper.readTree(read(ROOT + "manifest.json"));
        } catch (Exception e) {
            state.complete("ERROR", "Could not read the RFW-12316 manifest: " + e.getMessage());
            return;
        }
        JsonNode topics = manifest.path("topics");
        int waitSeconds = manifest.path("waitSeconds").asInt(120);
        int pollSeconds = Math.max(5, manifest.path("pollSeconds").asInt(10));
        int confirmAbsentSeconds = Math.max(5, manifest.path("confirmAbsentSeconds").asInt(20));
        boolean retryRuntime = request != null && Boolean.TRUE.equals(request.retryRuntimeAssertions());
        int runtimeRetries = retryRuntime
                ? Math.max(1, request.runtimeAssertRetries() == null ? 6 : request.runtimeAssertRetries())
                : 0;
        int runtimeRetrySeconds = retryRuntime
                ? Math.max(5, request.runtimeAssertRetrySeconds() == null ? 30 : request.runtimeAssertRetrySeconds())
                : 0;
        state.putInjection("topics", mapper.convertValue(topics, Object.class));
        state.putInjection("waitSeconds", waitSeconds);
        state.putInjection("pollSeconds", pollSeconds);
        state.putInjection("retryRuntimeAssertions", retryRuntime);
        state.putInjection("runtimeAssertRetries", runtimeRetries);
        state.putInjection("runtimeAssertRetrySeconds", runtimeRetrySeconds);
        state.putInjection("projectId", props.getProjectId());

        RunPhase reset = state.addPhase("Reset");
        state.startPhase(reset, "Removing previous reserved RFW-12316 documents");
        try {
            resetReserved(state.getEnv(), manifest.path("cleanup"));
        } catch (Exception e) {
            log.warn("rfw-12316 | reset failed | {}", e.getMessage());
            state.finishPhase(reset, PhaseStatus.FAILED, e.getMessage());
            state.complete("ERROR", "Reset failed: " + e.getMessage());
            return;
        }
        state.finishPhase(reset, PhaseStatus.DONE, "Reserved documents removed");

        List<ScenarioResult> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int errored = 0;
        for (JsonNode stage : manifest.path("stages")) {
            String id = stage.path("id").asText();
            String title = stage.path("title").asText();
            RunPhase phase = state.addPhase(id + " " + title);
            state.startPhase(phase, "Publishing " + id);
            CheckResult result;
            try {
                publishStage(stage.path("publish"), topics, phase);
                result = waitAndAssert(state.getEnv(), stage, phase, waitSeconds, pollSeconds,
                        confirmAbsentSeconds, runtimeRetries, runtimeRetrySeconds);
            } catch (Exception e) {
                log.warn("rfw-12316 | {} failed | {}", id, e.getMessage());
                result = CheckResult.error(id, e.getMessage());
            }
            switch (result.status()) {
                case PASS -> passed++;
                case FAIL -> failed++;
                default -> errored++;
            }
            state.finishPhase(phase, result.status() == CheckStatus.PASS ? PhaseStatus.DONE : PhaseStatus.FAILED,
                    result.message());
            results.add(new ScenarioResult(definition(id, title, expectedText(stage)), result));
            if (result.status() == CheckStatus.ERROR) {
                break;
            }
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

    private void resetReserved(String env, JsonNode cleanup) {
        List<String> products = texts(cleanup.path("products"));
        List<String> variants = texts(cleanup.path("variants"));
        List<String> skus = texts(cleanup.path("skus"));
        for (String database : List.of(CONFIG_DB, RUNTIME_DB)) {
            deleteIds(env, database, "Product", products);
            deleteIds(env, database, "Variant", variants);
            deleteIds(env, database, "SKU", skus);
            deleteIds(env, database, "Price", skus);
            // Also drop any residual child whose parent is a reserved product but whose own id
            // (different colour/size) is not in the exact id lists. A leftover sibling variant/SKU
            // would otherwise give a reserved product more than one child and defeat the
            // single-child "merged" parent-status rule (seen as config status inactive vs merged).
            deleteByField(env, database, "Variant", "productId", products);
            deleteByField(env, database, "SKU", "productId", products);
        }
        if (!skus.isEmpty()) {
            mongo.database(env, CONFIG_DB).getCollection("Errors")
                    .deleteMany(Filters.in("itemCode", skus));
        }
    }

    private void deleteIds(String env, String database, String collection, List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        mongo.database(env, database).getCollection(collection).deleteMany(Filters.in("_id", ids));
    }

    private void deleteByField(String env, String database, String collection, String field, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        mongo.database(env, database).getCollection(collection).deleteMany(Filters.in(field, values));
    }

    private void publishStage(JsonNode files, JsonNode topics, RunPhase phase) throws Exception {
        if (files == null || !files.isArray()) {
            return;
        }
        for (JsonNode file : files) {
            String topicKey = file.path("topic").asText();
            String topic = topics.path(topicKey).asText();
            if (topic.isBlank()) {
                throw new IllegalStateException("No topic configured for " + topicKey);
            }
            String name = file.path("file").asText();
            String json = read(ROOT + name);
            String messageId = pubSub.publish(props.getProjectId(), topic, new PublishMessageRequest(json, null, null));
            phase.setDetail("Published " + name + " to " + topicKey + " id " + messageId);
        }
    }

    private CheckResult waitAndAssert(String env, JsonNode stage, RunPhase phase,
                                      int waitSeconds, int pollSeconds, int confirmAbsentSeconds,
                                      int runtimeRetries, int runtimeRetrySeconds) throws InterruptedException {
        String id = stage.path("id").asText();
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        CheckResult latest = assertStage(env, stage);
        while (latest.status() != CheckStatus.PASS && System.currentTimeMillis() < deadline) {
            phase.setDetail(id + " not ready. " + latest.message());
            sleep(pollSeconds);
            latest = assertStage(env, stage);
        }
        for (int attempt = 1; attempt <= runtimeRetries && runtimeStillArriving(latest); attempt++) {
            phase.setDetail("Runtime not ready for " + id + ". Retry " + attempt + " of "
                    + runtimeRetries + " in " + runtimeRetrySeconds + "s");
            sleep(runtimeRetrySeconds);
            latest = assertStage(env, stage);
        }
        if (latest.status() == CheckStatus.PASS && stage.path("confirmAbsent").asBoolean(false)) {
            phase.setDetail("Confirming runtime stays absent for " + id);
            sleep(confirmAbsentSeconds);
            latest = assertStage(env, stage);
        }
        return latest;
    }

    /** True when a runtime document that should exist has not been written yet. */
    private static boolean runtimeStillArriving(CheckResult result) {
        return result.status() == CheckStatus.FAIL
                && result.message() != null
                && result.message().contains("runtime ")
                && !result.message().contains("was present");
    }

    private CheckResult assertStage(String env, JsonNode stage) {
        String id = stage.path("id").asText();
        if (stage.path("expectError").asBoolean(false)) {
            return assertRejected(env, id, stage.path("errorItemCode").asText());
        }
        List<String> problems = new ArrayList<>();
        MongoCollection<Document> configProducts = mongo.database(env, CONFIG_DB).getCollection("Product");
        MongoCollection<Document> configVariants = mongo.database(env, CONFIG_DB).getCollection("Variant");
        MongoCollection<Document> configSkus = mongo.database(env, CONFIG_DB).getCollection("SKU");
        MongoCollection<Document> configPrices = mongo.database(env, CONFIG_DB).getCollection("Price");
        MongoCollection<Document> runtimeProducts = mongo.database(env, RUNTIME_DB).getCollection("Product");
        MongoCollection<Document> runtimeVariants = mongo.database(env, RUNTIME_DB).getCollection("Variant");
        MongoCollection<Document> runtimeSkus = mongo.database(env, RUNTIME_DB).getCollection("SKU");
        MongoCollection<Document> runtimePrices = mongo.database(env, RUNTIME_DB).getCollection("Price");

        for (JsonNode productSpec : stage.path("products")) {
            String productId = productSpec.path("productId").asText();
            String variantId = productSpec.path("variantId").asText();
            String banner = productSpec.path("banner").asText();
            String configStatus = productSpec.path("configStatus").asText();
            String enrichment = productSpec.path("enrichmentStatus").asText();
            boolean publishedAt = productSpec.path("publishedAt").asBoolean(false);
            String runtime = productSpec.path("runtime").asText();

            checkConfig(problems, "Product", configProducts.find(Filters.eq("_id", productId)).first(),
                    configStatus, enrichment, banner, publishedAt);
            checkConfig(problems, "Variant", configVariants.find(Filters.eq("_id", variantId)).first(),
                    configStatus, enrichment, banner, publishedAt);
            checkRuntime(problems, "Product", runtimeProducts.find(Filters.eq("_id", productId)).first(),
                    runtime, banner, publishedAt, PRODUCT_FLAGS);
            checkRuntime(problems, "Variant", runtimeVariants.find(Filters.eq("_id", variantId)).first(),
                    runtime, banner, publishedAt, VARIANT_FLAGS);

            for (JsonNode skuSpec : productSpec.path("skus")) {
                String skuId = skuSpec.path("id").asText();
                Document sku = configSkus.find(Filters.eq("_id", skuId)).first();
                checkConfig(problems, "SKU " + skuId, sku, skuSpec.path("configStatus").asText(),
                        enrichment, banner, publishedAt);
                if (sku != null) {
                    Object merged = sku.get("isMerged");
                    boolean expectedMerged = skuSpec.path("merged").asBoolean();
                    if (!(merged instanceof Boolean) || ((Boolean) merged) != expectedMerged) {
                        problems.add("config SKU " + skuId + " isMerged was " + merged + ", expected " + expectedMerged);
                    }
                    if (skuSpec.has("isSale") && !skuSpec.get("isSale").isNull()) {
                        Object isSale = sku.get("isSale");
                        boolean expectedSale = skuSpec.path("isSale").asBoolean();
                        if (!(isSale instanceof Boolean) || ((Boolean) isSale) != expectedSale) {
                            problems.add("config SKU " + skuId + " isSale was " + isSale + ", expected " + expectedSale);
                        }
                    }
                }
                checkRuntime(problems, "SKU " + skuId, runtimeSkus.find(Filters.eq("_id", skuId)).first(),
                        skuSpec.path("runtime").asText(), banner, publishedAt, SKU_FLAGS);
            }
        }

        for (JsonNode priceSpec : stage.path("prices")) {
            String priceId = priceSpec.path("id").asText();
            checkPrice(problems, "config Price " + priceId,
                    configPrices.find(Filters.eq("_id", priceId)).first(), priceSpec, true);
            boolean runtimePrice = "present".equals(priceSpec.path("runtime").asText());
            checkPrice(problems, "runtime Price " + priceId,
                    runtimePrices.find(Filters.eq("_id", priceId)).first(), priceSpec, runtimePrice);
        }

        String expected = expectedText(stage);
        if (problems.isEmpty()) {
            return CheckResult.pass(id, 1, id + " matched. " + expected);
        }
        String productId = stage.path("products").path(0).path("productId").asText(id);
        return CheckResult.fail(id, 1, problems.size(), String.join("; ", problems), expected,
                String.join("; ", problems), List.of(productId));
    }

    private CheckResult assertRejected(String env, String id, String itemCode) {
        Document sku = mongo.database(env, CONFIG_DB).getCollection("SKU")
                .find(Filters.eq("_id", itemCode)).first();
        if (sku != null) {
            return CheckResult.fail(id, 1, 1, "Missing banner was stored as SKU " + itemCode,
                    "no SKU and one Errors document", "SKU exists", List.of(itemCode));
        }
        Document error = mongo.database(env, CONFIG_DB).getCollection("Errors")
                .find(Filters.eq("itemCode", itemCode)).first();
        if (error == null) {
            return CheckResult.fail(id, 1, 1, "No Errors document contains " + itemCode,
                    "schema rejection in Errors", "Errors document was not created", List.of(itemCode));
        }
        return CheckResult.pass(id, 1, "Missing banner was rejected and " + itemCode + " was not stored");
    }

    private static void checkConfig(List<String> problems, String label, Document doc,
                                    String status, String enrichment, String banner, boolean publishedAt) {
        if (doc == null) {
            problems.add("config " + label + " was not created");
            return;
        }
        if (!status.equals(text(doc.get("status")))) {
            problems.add("config " + label + " status was " + text(doc.get("status")) + ", expected " + status);
        }
        if (!enrichment.equals(text(doc.get("enrichmentStatus")))) {
            problems.add("config " + label + " enrichmentStatus was " + text(doc.get("enrichmentStatus"))
                    + ", expected " + enrichment);
        }
        if (!banner.equals(text(doc.get("banner")))) {
            problems.add("config " + label + " banner was " + text(doc.get("banner")) + ", expected " + banner);
        }
        if (publishedAt != hasPublishedAt(doc)) {
            problems.add("config " + label + (publishedAt ? " publishedAt was missing" : " publishedAt was set"));
        }
    }

    /**
     * {@code active} requires the runtime document. {@code inactive} requires it and forbids status
     * active. {@code absent} fails when any runtime document is present.
     */
    private static void checkRuntime(List<String> problems, String label, Document doc, String runtime,
                                     String banner, boolean publishedAt, List<String> flags) {
        if ("absent".equals(runtime)) {
            if (doc != null) {
                problems.add("runtime " + label + " was present");
            }
            return;
        }
        if (doc == null) {
            problems.add("runtime " + label + " was not created");
            return;
        }
        if ("active".equals(runtime)) {
            if (!"active".equals(text(doc.get("status")))) {
                problems.add("runtime " + label + " status was " + text(doc.get("status")) + ", expected active");
            }
            if (!banner.equals(text(doc.get("banner")))) {
                problems.add("runtime " + label + " banner was " + text(doc.get("banner")) + ", expected " + banner);
            }
            if (publishedAt && !hasPublishedAt(doc)) {
                problems.add("runtime " + label + " publishedAt was missing");
            }
            checkFlags(problems, label, doc, flags);
            return;
        }
        if ("active".equals(text(doc.get("status")))) {
            problems.add("runtime " + label + " status was active");
        }
    }

    private static void checkPrice(List<String> problems, String label, Document doc, JsonNode spec, boolean required) {
        if (!required) {
            if (doc != null) {
                problems.add(label + " was present");
            }
            return;
        }
        if (doc == null) {
            problems.add(label + " was not created");
            return;
        }
        checkAmount(problems, label, doc, "listPrice", spec);
        checkAmount(problems, label, doc, "salePrice", spec);
        checkAmount(problems, label, doc, "promoPrice", spec);
        checkAmount(problems, label, doc, "finalPrice", spec);
    }

    private static void checkAmount(List<String> problems, String label, Document doc, String field, JsonNode spec) {
        if (!spec.has(field) || spec.get(field).isNull()) {
            return;
        }
        double expected = spec.path(field).asDouble();
        Object actual = doc.get(field);
        if (!samePrice(actual, expected)) {
            problems.add(label + " " + field + " was " + actual + ", expected " + expected);
        }
    }

    private static void checkFlags(List<String> problems, String label, Document doc, List<String> flags) {
        for (String flag : flags) {
            Object value = doc.get(flag);
            if (!(value instanceof Boolean)) {
                problems.add("runtime " + label + " " + flag + " was " + value + ", expected true or false");
            }
        }
    }

    private static boolean hasPublishedAt(Document doc) {
        if (doc == null || !doc.containsKey("publishedAt") || doc.get("publishedAt") == null) {
            return false;
        }
        Object value = doc.get("publishedAt");
        return !(value instanceof String text && text.isBlank());
    }

    private static boolean samePrice(Object actual, double expected) {
        if (!(actual instanceof Number number)) {
            return false;
        }
        return Math.abs(number.doubleValue() - expected) < 0.001;
    }

    private static String expectedText(JsonNode stage) {
        if (stage.path("expectError").asBoolean(false)) {
            return "no stored SKU; one Errors document for " + stage.path("errorItemCode").asText();
        }
        JsonNode product = stage.path("products").path(0);
        return "config status " + product.path("configStatus").asText()
                + ", enrichment " + product.path("enrichmentStatus").asText()
                + ", runtime " + product.path("runtime").asText()
                + ", banner " + product.path("banner").asText();
    }

    private static ScenarioDef definition(String id, String title, String expected) {
        return new ScenarioDef(id, ScenarioGroup.UNIVERSE_ITEM, "RFW-12316", title, "P1",
                Feasibility.READONLY, title, expected);
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
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
