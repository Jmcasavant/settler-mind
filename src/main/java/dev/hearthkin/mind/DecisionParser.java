package dev.hearthkin.mind;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns raw model output into a {@link Decision}, or explains why it can't. This is the
 * trust boundary: model text is untrusted, and nothing reaches the body without passing
 * through here. Invalid steps are dropped with an error (so the mind can be told once),
 * speech is sanitised, and coordinates must be things the settler actually perceived.
 */
public final class DecisionParser {
    public record Result(Decision decision, List<String> errors) {
        public boolean clean() { return errors.isEmpty(); }
    }

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern FORMATTING = Pattern.compile("§.|[\\p{Cntrl}&&[^\n]]");
    static final int MAX_SAY = 220;

    private DecisionParser() {}

    public static Result parse(String raw, Perception seen) {
        List<String> errors = new ArrayList<>();
        JsonObject o;
        try {
            o = JsonParser.parseString(stripFences(raw)).getAsJsonObject();
        } catch (RuntimeException e) {
            return new Result(Decision.idle("(I lost my train of thought.)"), List.of("not valid JSON: " + e.getMessage()));
        }

        String thought = clean(str(o, "thought"), 400);
        String say = clean(str(o, "say"), MAX_SAY);
        if (say != null && say.isBlank()) say = null;
        String sayTo = clean(str(o, "say_to"), 32);
        String mood = clean(str(o, "mood"), 24);
        String goal = clean(str(o, "goal"), 120);

        List<Decision.Step> steps = new ArrayList<>();
        JsonArray rawSteps = arr(o, "steps");
        for (int i = 0; i < rawSteps.size(); i++) {
            if (steps.size() >= ActionCatalog.MAX_STEPS) {
                errors.add("too many steps; only the first " + ActionCatalog.MAX_STEPS + " were kept");
                break;
            }
            if (!rawSteps.get(i).isJsonObject()) { errors.add("step " + i + " is not an object"); continue; }
            JsonObject s = rawSteps.get(i).getAsJsonObject();
            String kind = str(s, "kind");
            String err = validateStep(kind, s, seen);
            if (err != null) {
                errors.add("step " + i + " (" + kind + "): " + err);
                break; // later steps likely depend on this one
            }
            steps.add(new Decision.Step(kind, clean(str(s, "target"), 32), intOrNull(s, "x"), intOrNull(s, "y"),
                    intOrNull(s, "z"), intOrNull(s, "seconds")));
        }

        List<Decision.Feeling> feelings = new ArrayList<>();
        JsonArray rawFeelings = arr(o, "feelings");
        for (int i = 0; i < rawFeelings.size() && feelings.size() < 3; i++) {
            if (!rawFeelings.get(i).isJsonObject()) continue;
            JsonObject f = rawFeelings.get(i).getAsJsonObject();
            String who = clean(str(f, "who"), 32);
            Integer change = intOrNull(f, "change");
            if (who == null || change == null) continue;
            feelings.add(new Decision.Feeling(who, Math.max(-2, Math.min(2, change)), clean(str(f, "why"), 100)));
        }

        Decision.Remember remember = null;
        if (o.has("remember") && o.get("remember").isJsonObject()) {
            JsonObject r = o.getAsJsonObject("remember");
            String text = clean(str(r, "text"), 200);
            Integer imp = intOrNull(r, "importance");
            if (text != null && !text.isBlank()) remember = new Decision.Remember(text, imp == null ? 5 : Math.max(1, Math.min(10, imp)));
        }

        return new Result(new Decision(thought == null ? "" : thought, say, sayTo,
                mood == null ? "calm" : mood.toLowerCase(), goal, List.copyOf(steps), List.copyOf(feelings), remember), errors);
    }

    /** @return null if valid, otherwise why not. */
    static String validateStep(String kind, JsonObject s, Perception seen) {
        if (kind == null || !ActionCatalog.known(kind)) return "unknown action; choose from the list";
        switch (kind) {
            case "mine" -> {
                Integer x = intOrNull(s, "x"), y = intOrNull(s, "y"), z = intOrNull(s, "z");
                if (x == null || y == null || z == null) return "needs x, y, z";
                if (!seen.canSeeBlock(x, y, z)) return "you can't see a block there; use coordinates from what you see";
            }
            case "go_to" -> {
                Integer x = intOrNull(s, "x"), y = intOrNull(s, "y"), z = intOrNull(s, "z");
                if (x == null || y == null || z == null) return "needs x, y, z";
                int[] p = seen.position();
                double d = Math.sqrt(Math.pow(x - p[0], 2) + Math.pow(y - p[1], 2) + Math.pow(z - p[2], 2));
                if (d > ActionCatalog.MAX_WALK) return "too far (" + Math.round(d) + " blocks); max " + ActionCatalog.MAX_WALK;
            }
            case "follow" -> {
                String t = str(s, "target");
                if (t == null || !seen.canSee("player", t)) return "you can't see a player called " + t;
            }
            case "wait" -> {
                Integer sec = intOrNull(s, "seconds");
                if (sec == null || sec < 1 || sec > 60) return "seconds must be 1-60";
            }
            default -> { }
        }
        return null;
    }

    /** Remove formatting codes, control characters and links; trim to length. */
    static String clean(String s, int max) {
        if (s == null) return null;
        String out = URL.matcher(FORMATTING.matcher(s).replaceAll("")).replaceAll("[link removed]").trim();
        return out.length() <= max ? out : out.substring(0, max - 1) + "…";
    }

    private static String stripFences(String raw) {
        String t = raw.trim();
        if (t.startsWith("```")) {
            int start = t.indexOf('\n');
            int end = t.lastIndexOf("```");
            if (start > 0 && end > start) t = t.substring(start + 1, end);
        }
        return t;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static Integer intOrNull(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        double d = e.getAsDouble();
        return Double.isFinite(d) && Math.abs(d) < 30_000_000 ? (int) Math.floor(d) : null;
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
