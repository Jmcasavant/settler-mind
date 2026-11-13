package dev.settlermind.mind.cognition;

import org.jetbrains.annotations.Nullable;

/**
 * The raw reply and what it cost. {@code error} is set when no usable text came back
 * (timeout, refusal, provider outage); the mind then falls back to safe idle behaviour.
 */
public record CognitionResult(
        @Nullable String text,
        String provider,
        String model,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long latencyMs,
        @Nullable String error) {

    public static CognitionResult failed(String provider, String model, long latencyMs, String error) {
        return new CognitionResult(null, provider, model, 0, 0, 0, 0, latencyMs, error);
    }

    public boolean ok() { return error == null && text != null; }
}
