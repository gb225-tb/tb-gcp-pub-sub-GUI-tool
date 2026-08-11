package com.internal.tools.pubsubgui.automation.ai;

import com.internal.tools.pubsubgui.automation.model.AiAnalyzeRequest;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point for AI failure analysis. Selects the configured provider (openai / cursor) and always
 * degrades to the offline {@link HeuristicAnalyzer} when the provider is unconfigured or errors out,
 * so the UI never breaks.
 */
@Service
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    private final AiProperties props;
    private final AiAnalyzer heuristic = new HeuristicAnalyzer();
    private final AiAnalyzer openAi;
    private final AiAnalyzer cursor;

    public AiAnalysisService(AiProperties props) {
        this.props = props;
        this.openAi = new OpenAiCompatibleAnalyzer(props);
        this.cursor = new CursorAgentAnalyzer(props);
    }

    /** Analyze a set of failures, honoring the configured provider with heuristic fallback. */
    public AiAnalyzeResponse analyze(AiAnalyzeRequest request) {
        String provider = props.effectiveProvider();
        try {
            return switch (provider) {
                case "openai" -> openAi.analyze(request);
                case "cursor" -> cursor.analyze(request);
                default -> heuristic.analyze(request);
            };
        } catch (RuntimeException e) {
            log.warn("automation ai | provider={} failed, falling back to heuristic | {}",
                    provider, e.getMessage());
            AiAnalyzeResponse fallback = heuristic.analyze(request);
            return new AiAnalyzeResponse(fallback.provider(), false,
                    "(" + provider + " provider failed: " + e.getMessage() + ")\n\n" + fallback.analysis());
        }
    }

    /** Status for the UI: which provider is active and whether a real LLM is wired. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        String effective = props.effectiveProvider();
        out.put("configuredProvider", props.getProvider());
        out.put("effectiveProvider", effective);
        out.put("llmConfigured", !"heuristic".equals(effective));
        out.put("model", "openai".equals(effective) ? props.getModel()
                : "cursor".equals(effective) ? (props.getCursorModel().isBlank() ? "default" : props.getCursorModel())
                : null);
        return out;
    }
}
