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

    // ------------------------------------------------------------ coordination (docs/BOARD.md "Evaluation")
    /** Board tasks seeded by the scenario plus any posted during the run. */
    public int tasksTotal;
    public int tasksDone;
    /** Per DONE task: ticks from posting to done. */
    public final Map<String, Long> ticksToDone = new TreeMap<>();
    /** Σ min(gathered across settlers, required) / Σ required over the seeded tasks; null when nothing was required. */
    public Double needsMet;
    /** Gathered across settlers at the end, per required item. */
    public final Map<String, Integer> gathered = new TreeMap<>();
    public int boardPosts;
    public int claimAttempts;
    public int claimsGranted;
    /** Rejected claims by code (stale, not_open, too_many_claims, ...): the CAS doing its job. */
    public final Map<String, Integer> claimsRejected = new TreeMap<>();
    /**
     * Grants made while another settler's claim on the same task was still open, derived at the end of the
     * run from {@link #claimHistory} (not from the board's state). Must stay 0.
     */
    public int doubleClaims;
    /** Samples of the overlaps behind {@link #doubleClaims}. */
    public final List<String> doubleClaimSamples = new ArrayList<>();
    /** Extra verified gains from a block that had already yielded (two settlers credited for one block). Must stay 0. */
    public int doubleCredits;
    /** Every claim/release/lapse/done/cancel and every verified block gain, in order. */
    public final ClaimHistory claimHistory = new ClaimHistory();
    /**
     * Claims carried out on an outdated view: the task's revision when the claim ran differed from the one in
     * the perception the decision was made from (decision latency, or the plan's earlier steps taking time).
     */
    public int claimsOnOutdatedView;
    /** Outdated-view claims that were granted anyway: revision fencing failed. Must stay 0. */
    public int fencingViolations;
    /** Rejected settler posts by code (e.g. invalid_item). */
    public final Map<String, Integer> postsRejected = new TreeMap<>();
    public int releases;
    public int expiredClaims;
    /** Mines refused because another settler held the block reservation. */
    public int reservationConflicts;
    /** Mines whose block was already gone (target_missing) or changed while breaking (target_changed): wasted work. */
    public int wastedWork;
    /** Mines abandoned part-way because a new decision replaced the plan (the host cancels the running skill). */
    public int minesCancelled;
    /** Final state of every task, e.g. {@code T1 DONE oak_log 3/3 by Fern}. */
    public final List<String> boardFinal = new ArrayList<>();

    void claim(String code, boolean outdatedView) {
        claimAttempts++;
        if (outdatedView) claimsOnOutdatedView++;
        if ("ok".equals(code)) {
            claimsGranted++;
            if (outdatedView) fencingViolations++;
        } else {
            claimsRejected.merge(code, 1, Integer::sum);
        }
    }

    /** Derive the history-based measures (call once the run is over). */
    void deriveFromHistory() {
        doubleClaims = claimHistory.doubleClaims();
        doubleClaimSamples.clear();
        doubleClaimSamples.addAll(claimHistory.doubleClaimSamples(5));
        doubleCredits = claimHistory.doubleCredits();
    }

    /** Model calls per DONE task (all routes), or null when no task was done. Set at the end of the run. */
    public Double callsPerDoneTask;

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
