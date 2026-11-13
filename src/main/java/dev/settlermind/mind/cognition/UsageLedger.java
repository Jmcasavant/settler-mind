package dev.settlermind.mind.cognition;

import java.util.Map;
import java.util.TreeMap;

/**
 * Running totals per route and model: calls, failures, tokens, latency and estimated
 * cost. This is the evidence for choosing models; prices come from config and are
 * estimates, not a bill.
 */
public final class UsageLedger {
    public static final class Line {
        public long calls, failures, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, latencyMsTotal;
        public double estimatedUsd;

        public String summary() {
            long avgLatency = calls == 0 ? 0 : latencyMsTotal / calls;
            return calls + " calls (" + failures + " failed), in " + inputTokens + " / out " + outputTokens
                    + " tok, cache read " + cacheReadTokens + ", avg " + avgLatency + " ms, ~$"
                    + String.format(java.util.Locale.ROOT, "%.4f", estimatedUsd);
        }
    }

    /** USD per million tokens. */
    public record Price(double input, double output, double cacheRead, double cacheWrite) {}

    private final Map<String, Line> lines = new TreeMap<>();
    private final Map<String, Price> prices;

    public UsageLedger(Map<String, Price> prices) {
        this.prices = prices;
    }

    public synchronized void record(String route, CognitionResult r) {
        Line l = lines.computeIfAbsent(route + " / " + r.provider() + ":" + r.model(), k -> new Line());
        l.calls++;
        if (!r.ok()) l.failures++;
        l.inputTokens += r.inputTokens();
        l.outputTokens += r.outputTokens();
        l.cacheReadTokens += r.cacheReadTokens();
        l.cacheWriteTokens += r.cacheWriteTokens();
        l.latencyMsTotal += r.latencyMs();
        Price p = prices.get(r.model());
        if (p != null) {
            l.estimatedUsd += (r.inputTokens() * p.input() + r.outputTokens() * p.output()
                    + r.cacheReadTokens() * p.cacheRead() + r.cacheWriteTokens() * p.cacheWrite()) / 1_000_000.0;
        }
    }

    public synchronized Map<String, Line> snapshot() {
        return new TreeMap<>(lines);
    }

    public synchronized double totalUsd() {
        return lines.values().stream().mapToDouble(l -> l.estimatedUsd).sum();
    }
}
