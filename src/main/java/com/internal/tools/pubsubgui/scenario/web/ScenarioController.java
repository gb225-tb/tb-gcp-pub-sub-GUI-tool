package com.internal.tools.pubsubgui.scenario.web;

import com.internal.tools.pubsubgui.automation.ai.AiAnalysisService;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeRequest;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeResponse;
import com.internal.tools.pubsubgui.scenario.GithubActionsService;
import com.internal.tools.pubsubgui.scenario.ScenarioCatalog;
import com.internal.tools.pubsubgui.scenario.ScenarioRunService;
import com.internal.tools.pubsubgui.scenario.config.ScenarioProperties;
import com.internal.tools.pubsubgui.scenario.model.ScenarioKind;
import com.internal.tools.pubsubgui.scenario.model.ScenarioRunRequest;
import com.internal.tools.pubsubgui.scenario.model.ScenarioRunState;
import com.internal.tools.pubsubgui.scenario.model.ScenarioSpec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the Perf-only Scenario Runner. The catalog/sample/status calls are cheap; the run
 * start returns immediately with a run id that the UI polls via {@code /run/{id}}. Injection is
 * hard-guarded to Perf in {@link ScenarioRunService}. AI analysis re-uses the existing service.
 */
@RestController
@RequestMapping("/api/scenario")
public class ScenarioController {

    private final ScenarioCatalog catalog;
    private final ScenarioRunService runService;
    private final ScenarioProperties props;
    private final GithubActionsService github;
    private final AiAnalysisService ai;

    public ScenarioController(ScenarioCatalog catalog, ScenarioRunService runService, ScenarioProperties props,
                              GithubActionsService github, AiAnalysisService ai) {
        this.catalog = catalog;
        this.runService = runService;
        this.props = props;
        this.github = github;
        this.ai = ai;
    }

    /** The scenario catalog + Perf-only + github status metadata that drives the UI. */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (ScenarioSpec s : catalog.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("category", s.category().name());
            m.put("categoryLabel", s.category().label());
            m.put("shortName", s.shortName());
            m.put("kind", s.kind().name());
            m.put("processor", s.processor());
            m.put("description", s.description());
            m.put("enabled", s.enabled());
            m.put("target", s.target());
            m.put("topicId", s.topicId());
            m.put("gcsBucket", s.gcsBucket());
            m.put("gcsObjectPrefix", s.gcsObjectPrefix());
            m.put("defaultFileName", s.defaultFileName());
            m.put("githubRepo", s.githubRepo());
            m.put("workflowFile", s.workflowFile());
            m.put("verifyMode", s.verifyMode().name());
            m.put("verifyTarget", verifyTarget(s));
            m.put("requiresFullFeed", s.requiresFullFeed());
            m.put("supportsCleanup", s.supportsCleanup());
            scenarios.add(m);
        }
        List<Map<String, Object>> categories = new ArrayList<>();
        for (var c : com.internal.tools.pubsubgui.scenario.model.ScenarioCategory.values()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.name());
            cm.put("label", c.label());
            categories.add(cm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", categories);
        out.put("scenarios", scenarios);
        out.put("perfOnly", props.isEnforcePerfOnly());
        out.put("perfEnv", ScenarioProperties.PERF);
        out.put("projectId", props.getProjectId());
        out.put("streamWaitSeconds", props.getStreamWaitSeconds());
        out.put("batchTimeoutSeconds", props.getBatchTimeoutSeconds());
        out.put("github", github.status());
        return out;
    }

    /** Short, human-readable description of what the verify step checks (for the UI run details). */
    private static String verifyTarget(ScenarioSpec s) {
        return switch (s.verifyMode()) {
            case CATALOG_VALIDATORS -> "item-config validators (" + s.verifyGroup() + ")";
            case CATALOG_PRESENCE -> {
                String field = s.verifyMatchField() == null || s.verifyMatchField().isBlank()
                        ? "_id" : s.verifyMatchField();
                String assertion = s.verifyAssertField() == null || s.verifyAssertField().isBlank()
                        ? "" : " asserting " + s.verifyAssertField() + "=" + s.verifyAssertValue();
                yield "item-config." + s.verifyCollection() + " by " + field + assertion;
            }
            case INVENTORY_PRESENCE -> {
                String coll = s.verifyCollection() != null && !s.verifyCollection().isBlank()
                        ? s.verifyCollection()
                        : ("inventory-runtime".equalsIgnoreCase(s.verifyItemDb()) ? "Inventory" : "Item");
                yield s.verifyItemDb() + "." + coll;
            }
            default -> "none";
        };
    }

    /** The bundled sample (prefill for streaming editor / default batch file). */
    @GetMapping("/sample/{id}")
    public ResponseEntity<Map<String, Object>> sample(@PathVariable String id) {
        ScenarioSpec spec = catalog.find(id).orElse(null);
        if (spec == null) {
            return ResponseEntity.notFound().build();
        }
        String content;
        try (InputStream in = new ClassPathResource(spec.sampleResource()).getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            content = "";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", spec.id());
        out.put("kind", spec.kind().name());
        out.put("fileName", spec.kind() == ScenarioKind.BATCH ? spec.defaultFileName() : null);
        out.put("content", content);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/github/status")
    public Map<String, Object> githubStatus() {
        return github.status();
    }

    /** Start a run (Perf-guarded); returns the initial state with a run id to poll. */
    @PostMapping("/run")
    public Mono<ResponseEntity<ScenarioRunState>> run(@RequestBody ScenarioRunRequest request) {
        return Mono.fromCallable(() -> ResponseEntity.ok(runService.start(request)))
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Poll a run's live state. */
    @GetMapping("/run/{id}")
    public ResponseEntity<ScenarioRunState> runStatus(@PathVariable String id) {
        ScenarioRunState state = runService.get(id);
        return state == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(state);
    }

    /** Explain failures with AI (LLM if configured, heuristic otherwise). */
    @PostMapping("/analyze")
    public Mono<AiAnalyzeResponse> analyze(@RequestBody AiAnalyzeRequest request) {
        return Mono.fromCallable(() -> ai.analyze(request)).subscribeOn(Schedulers.boundedElastic());
    }
}
