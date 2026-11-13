package dev.settlermind.mind.cognition;

/**
 * How one kind of thinking is served. Every field comes from config, so models can be
 * swapped per route (and compared using the usage ledger) without code changes.
 *
 * @param provider  "scripted", "claude", ...
 * @param model     provider model id, e.g. "claude-opus-5-5"; ignored by "scripted"
 * @param effort    provider effort hint: "low", "medium", "high"
 * @param maxTokens output cap for this route
 */
public record RouteConfig(String provider, String model, String effort, int maxTokens) {
}
