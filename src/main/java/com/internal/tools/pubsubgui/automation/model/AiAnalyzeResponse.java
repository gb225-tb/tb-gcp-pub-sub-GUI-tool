package com.internal.tools.pubsubgui.automation.model;

/**
 * Result of an AI analysis request.
 *
 * @param provider  which analyzer produced the text (openai / cursor / heuristic)
 * @param configured whether a real LLM was configured (false => heuristic fallback text)
 * @param analysis  the explanation (markdown-ish plain text)
 */
public record AiAnalyzeResponse(String provider, boolean configured, String analysis) {
}
