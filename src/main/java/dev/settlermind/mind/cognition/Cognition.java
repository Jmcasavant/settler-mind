package dev.settlermind.mind.cognition;

import java.util.concurrent.CompletableFuture;

/**
 * A source of thought: an LLM provider, a local model, or the offline scripted mind.
 * Implementations must never touch game state; they receive text (plus, for the
 * scripted mind, the structured situation) and return text.
 */
public interface Cognition {
    /** Stable identifier used in config and the usage ledger, e.g. "claude" or "scripted". */
    String provider();

    CompletableFuture<CognitionResult> complete(CognitionRequest request, RouteConfig route);
}
