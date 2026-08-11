package com.internal.tools.pubsubgui.automation;

import com.internal.tools.pubsubgui.automation.check.AutomationContext;
import com.internal.tools.pubsubgui.automation.model.CheckResult;
import com.internal.tools.pubsubgui.automation.model.RunRequest;
import com.internal.tools.pubsubgui.automation.model.RunSummary;
import com.internal.tools.pubsubgui.automation.model.ScenarioGroup;
import com.internal.tools.pubsubgui.automation.model.ScenarioResult;
import com.internal.tools.pubsubgui.automation.scenario.ScenarioRegistry;
import com.internal.tools.pubsubgui.config.MongoClientFactory;
import com.internal.tools.pubsubgui.service.HclBuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runs the selected scenarios' read-only validators against a single environment and aggregates the
 * outcomes into a {@link RunSummary}. All execution is synchronous/blocking here; the controller wraps
 * it on the bounded-elastic scheduler.
 */
@Service
public class AutomationEngine {

    private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private static final int DEFAULT_SAMPLE = 200;
    private static final int MIN_SAMPLE = 1;
    private static final int MAX_SAMPLE = 2000;

    private final ScenarioRegistry registry;
    private final MongoClientFactory mongo;
    private final HclBuildService hclBuildService;

    public AutomationEngine(ScenarioRegistry registry, MongoClientFactory mongo,
                            HclBuildService hclBuildService) {
        this.registry = registry;
        this.mongo = mongo;
        this.hclBuildService = hclBuildService;
    }

    public RunSummary run(RunRequest request) {
        if (request == null || request.env() == null || request.env().isBlank()) {
            throw new IllegalArgumentException("env is required");
        }
        int sampleSize = clampSample(request.sampleSize());
        String productId = trimToNull(request.productId());
        AutomationContext ctx = new AutomationContext(request.env().trim(), productId, sampleSize,
                mongo, hclBuildService);

        List<ScenarioRegistry.Entry> selected = select(request);

        Instant start = Instant.now();
        List<ScenarioResult> results = new ArrayList<>(selected.size());
        int passed = 0, failed = 0, skipped = 0, na = 0, errored = 0;
        for (ScenarioRegistry.Entry entry : selected) {
            String id = entry.def().id();
            CheckResult result;
            try {
                result = entry.validator().run(id, ctx);
            } catch (RuntimeException e) {
                log.warn("automation | env={} scenario={} failed to run | {}", request.env(), id,
                        rootMessage(e));
                result = CheckResult.error(id, "Check threw: " + rootMessage(e));
            }
            switch (result.status()) {
                case PASS -> passed++;
                case FAIL -> failed++;
                case SKIP -> skipped++;
                case NA -> na++;
                case ERROR -> errored++;
            }
            results.add(new ScenarioResult(entry.def(), result));
        }
        Instant end = Instant.now();

        return new RunSummary(
                request.env().trim(), productId, sampleSize,
                ISO.format(start), ISO.format(end), Duration.between(start, end).toMillis(),
                results.size(), passed, failed, skipped, na, errored, results);
    }

    /** Resolve which scenarios to run based on explicit ids, group filter, or "all". */
    private List<ScenarioRegistry.Entry> select(RunRequest request) {
        List<ScenarioRegistry.Entry> all = registry.all();
        List<String> ids = request.scenarioIds();
        if (ids != null && !ids.isEmpty()) {
            Set<String> want = Set.copyOf(ids);
            return all.stream().filter(e -> want.contains(e.def().id())).toList();
        }
        ScenarioGroup group = parseGroup(request.group());
        if (group != null) {
            return all.stream().filter(e -> e.def().group() == group).toList();
        }
        return all; // "All"
    }

    private static ScenarioGroup parseGroup(String group) {
        if (group == null || group.isBlank() || "ALL".equalsIgnoreCase(group.trim())) {
            return null;
        }
        for (ScenarioGroup g : ScenarioGroup.values()) {
            if (g.name().equalsIgnoreCase(group.trim()) || g.label().equalsIgnoreCase(group.trim())) {
                return g;
            }
        }
        return null;
    }

    private static int clampSample(Integer requested) {
        int v = requested == null ? DEFAULT_SAMPLE : requested;
        return Math.max(MIN_SAMPLE, Math.min(MAX_SAMPLE, v));
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (Objects.nonNull(cur.getCause()) && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }

    /** The scenario catalog (used by the controller's catalog endpoint). */
    public ScenarioRegistry registry() {
        return registry;
    }
}
