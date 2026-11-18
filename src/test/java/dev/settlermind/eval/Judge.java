package dev.settlermind.eval;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.settlermind.mind.Memory;
import dev.settlermind.mind.Mind;
import dev.settlermind.mind.cognition.Cognition;
import dev.settlermind.mind.cognition.CognitionRequest;
import dev.settlermind.mind.cognition.CognitionResult;
import dev.settlermind.mind.cognition.RouteConfig;
import dev.settlermind.mind.cognition.UsageLedger;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores interview answers against what the settler is and what actually happened to
 * it (Generative Agents-style interviews). The judge sees the persona and the full
 * memory record, so it can catch invented events. Scores are 1-5 per dimension.
 */
public final class Judge {
    public record Score(String settler, String question, String answer, int inCharacter, int grounded,
                        int natural, List<String> hallucinations, String note) {}

    static final String SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["in_character","grounded","natural","hallucinations","note"],
             "properties":{
              "in_character":{"type":"integer","description":"1-5: consistent with the persona's traits, values and speech style"},
              "grounded":{"type":"integer","description":"1-5: consistent with the memory record; 1 if it invents events"},
              "natural":{"type":"integer","description":"1-5: sounds like a real person in game chat, concise, not assistant-like"},
              "hallucinations":{"type":"array","items":{"type":"string"},"description":"Specific claims not supported by the record"},
              "note":{"type":"string"}}}""";

    static final String SYSTEM = """
            You evaluate characters in a game for believability. You are strict and consistent. \
            Judge only the answer given, against the persona and the memory record provided. \
            Claims the record does not support are hallucinations, unless they are clearly opinions or feelings. \
            Reply only with JSON.""";

    private final Cognition provider;
    private final RouteConfig route;
    private final UsageLedger ledger;

    public Judge(Cognition provider, RouteConfig route, UsageLedger ledger) {
        this.provider = provider;
        this.route = route;
        this.ledger = ledger;
    }

    public List<Score> score(Mind mind, List<String[]> qa) {
        List<Score> out = new ArrayList<>();
        StringBuilder record = new StringBuilder();
        for (Memory m : mind.memory().all()) record.append("- ").append(m.render(Long.MAX_VALUE / 2)).append('\n');
        for (String[] pair : qa) {
            String user = "## Persona\n" + mind.persona().name() + ". " + mind.persona().describe()
                    + "\n\n## Memory record (ground truth of what happened to them)\n" + record
                    + "\n## Question\n" + pair[0] + "\n\n## Their answer\n" + pair[1];
            CognitionRequest req = new CognitionRequest("judge", mind.persona().name(), SYSTEM, user, SCHEMA,
                    mind.persona(), null, List.of(), mind.relationships(), List.of());
            CognitionResult res = provider.complete(req, route).join();
            ledger.record("judge", res);
            if (!res.ok()) {
                out.add(new Score(mind.persona().name(), pair[0], pair[1], 0, 0, 0, List.of(), "judge error: " + res.error()));
                continue;
            }
            try {
                JsonObject o = JsonParser.parseString(res.text()).getAsJsonObject();
                List<String> h = new ArrayList<>();
                for (JsonElement e : o.getAsJsonArray("hallucinations")) h.add(e.getAsString());
                out.add(new Score(mind.persona().name(), pair[0], pair[1], clamp(o.get("in_character").getAsInt()),
                        clamp(o.get("grounded").getAsInt()), clamp(o.get("natural").getAsInt()), h, o.get("note").getAsString()));
            } catch (RuntimeException e) {
                out.add(new Score(mind.persona().name(), pair[0], pair[1], 0, 0, 0, List.of(), "unparseable judge reply"));
            }
        }
        return out;
    }

    private static int clamp(int v) { return Math.max(1, Math.min(5, v)); }
}
