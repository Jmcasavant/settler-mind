package dev.settlermind.eval;

import dev.settlermind.mind.Decision;
import dev.settlermind.mind.DecisionParser;
import dev.settlermind.mind.cognition.CognitionResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Counters collected during a simulation. Everything here is measured, not judged. */
public final class Metrics {
    public final Map<String, Integer> callsByRoute = new TreeMap<>();
    public int failedCalls;
    public long latencyMsTotal;
    public int decisions;
    public int parseErrors;
    public final List<String> parseErrorSamples = new ArrayList<>();
    public final Map<String, Integer> outcomes = new TreeMap<>();
    public final List<String> unfoundedClaims = new ArrayList<>();
    public final Map<String, Integer> utterances = new TreeMap<>();
    /** One entry per request, in order. */
    public record RequestResult(String settler, String requester, long tick, String result) {}
    public final List<RequestResult> requests = new ArrayList<>();
    private final Map<String, Deque<String[]>> pendingRequests = new LinkedHashMap<>();

    void call(String route, CognitionResult r) {
        callsByRoute.merge(route, 1, Integer::sum);
        if (!r.ok()) failedCalls++;
        latencyMsTotal += r.latencyMs();
    }

    void parsed(DecisionParser.Result p) {
        if (!p.clean()) {
            parseErrors++;
            if (parseErrorSamples.size() < 5) parseErrorSamples.add(String.join("; ", p.errors()));
        }
    }

    void decision(String settler, Decision d, long tick) {
        decisions++;
        if (d.say() != null) utterances.merge(settler, 1, Integer::sum);
        Deque<String[]> pending = pendingRequests.get(settler);
        if (pending == null || pending.isEmpty()) return;
        String[] req = pending.pollFirst();
        String result = !d.steps().isEmpty() ? "complied" : d.say() != null ? "declined" : "ignored";
        requests.add(new RequestResult(settler, req[0], Long.parseLong(req[1]), result));
    }

    void requestAsked(String settler, String requester, long tick) {
        pendingRequests.computeIfAbsent(settler, k -> new ArrayDeque<>()).addLast(new String[]{requester, Long.toString(tick)});
    }

    void outcome(String settler, String kind, String status, long tick) {
        outcomes.merge(status, 1, Integer::sum);
    }

    void unfoundedClaim(String settler, String text, long tick) {
        unfoundedClaims.add(settler + " @" + tick + ": " + text);
    }

    /** Requests never answered by a decision count as ignored. */
    void closeOpenRequests() {
        pendingRequests.forEach((settler, q) -> {
            for (String[] r : q) requests.add(new RequestResult(settler, r[0], Long.parseLong(r[1]), "ignored"));
            q.clear();
        });
    }

    public String result(String settler, String requester, int nth) {
        return requests.stream().filter(r -> r.settler().equals(settler) && r.requester().equals(requester))
                .skip(nth).map(RequestResult::result).findFirst().orElse("none");
    }

    public double parseErrorRate() {
        return decisions == 0 ? 0 : (double) parseErrors / decisions;
    }

    public int totalCalls() {
        return callsByRoute.values().stream().mapToInt(Integer::intValue).sum();
    }
}
