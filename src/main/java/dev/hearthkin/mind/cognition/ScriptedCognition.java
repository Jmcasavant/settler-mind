package dev.hearthkin.mind.cognition;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.hearthkin.mind.MindEvent;
import dev.hearthkin.mind.Perception;
import dev.hearthkin.mind.Persona;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A deterministic, offline stand-in for a language model. It produces the same JSON a
 * model would, so the whole pipeline (prompt, parse, validate, act, remember) runs in
 * tests and on servers with no AI configured. It is not meant to be clever; it is meant
 * to be predictable, free, and personality-aware enough to be a fair baseline.
 */
public final class ScriptedCognition implements Cognition {
    @Override
    public String provider() { return "scripted"; }

    @Override
    public CompletableFuture<CognitionResult> complete(CognitionRequest req, RouteConfig route) {
        long start = System.nanoTime();
        String text = switch (req.route()) {
            case "reflect" -> reflect(req);
            case "plan" -> plan(req);
            default -> decide(req);
        };
        long ms = (System.nanoTime() - start) / 1_000_000;
        return CompletableFuture.completedFuture(new CognitionResult(text, provider(), "scripted", 0, 0, 0, 0, ms, null));
    }

    private static String reflect(CognitionRequest req) {
        JsonObject out = new JsonObject();
        JsonArray insights = new JsonArray();
        List<String> mem = req.recentMemories();
        if (!mem.isEmpty()) {
            JsonObject i = new JsonObject();
            i.addProperty("text", "Looking back, what stands out is: " + mem.get(mem.size() - 1));
            i.addProperty("importance", 5);
            i.add("about", new JsonArray());
            insights.add(i);
        }
        out.add("insights", insights);
        return out.toString();
    }

    private static String plan(CognitionRequest req) {
        JsonObject out = new JsonObject();
        JsonArray a = new JsonArray();
        a.add("work toward my dream: " + req.persona().aspiration());
        a.add("keep fed and safe");
        if (req.persona().trait(Persona.EXTRAVERSION) >= 0.5) a.add("talk with my neighbours");
        out.add("intentions", a);
        return out.toString();
    }

    private static String decide(CognitionRequest req) {
        JsonObject d = base("calm");
        d.addProperty("situation_summary", req.persona().name() + " is getting by.");
        Perception p = req.perception();
        MindEvent trigger = req.triggers().isEmpty() ? null : req.triggers().get(req.triggers().size() - 1);

        if (trigger instanceof MindEvent.Heard h && h.addressedToMe()) {
            respondTo(req, h, d);
        } else if (trigger instanceof MindEvent.ActionFinished a && a.failed()) {
            d.addProperty("thought", "That didn't go as planned.");
            d.addProperty("say", "Hm. That didn't work (" + a.code().replace('_', ' ') + ").");
            remember(d, "When I tried to " + a.step() + " it failed: " + a.code(), 6);
            d.addProperty("mood", "frustrated");
        } else if (trigger instanceof MindEvent.Hurt hurt) {
            d.addProperty("say", "Ow! Careful!");
            d.addProperty("mood", "scared");
            feel(d, hurt.by(), -1, "hurt me");
        } else if (trigger instanceof MindEvent.Idle idle) {
            spendFreeTime(req, idle, p, d);
        }
        return d.toString();
    }

    private static void respondTo(CognitionRequest req, MindEvent.Heard h, JsonObject d) {
        Perception p = req.perception();
        String text = h.text().toLowerCase(Locale.ROOT);
        String who = h.speaker();
        double affinity = req.relationships().knows(who) ? req.relationships().get(who).affinity : 0;
        double willing = affinity + 0.5 * req.persona().trait(Persona.AGREEABLENESS);
        d.addProperty("say_to", who);

        if (containsAny(text, "idiot", "stupid", "useless", "shut up")) {
            feel(d, who, -2, "insulted me");
            d.addProperty("say", "That's not a kind thing to say.");
            d.addProperty("mood", "hurt");
            return;
        }
        if (containsAny(text, "thank", "please", "good job", "well done")) {
            feel(d, who, 1, "was kind to me");
        }
        if (containsAny(text, "wood", "log", "chop", "tree", "mine", "dig")) {
            boolean wantsWood = containsAny(text, "wood", "log", "chop", "tree");
            Optional<Perception.SeenBlock> target = p.blocks().stream()
                    .filter(b -> !wantsWood || b.id().contains("log"))
                    .min(Comparator.comparingDouble(Perception.SeenBlock::distance));
            if (target.isEmpty()) {
                d.addProperty("say", "I don't see any" + (wantsWood ? " wood" : "thing to dig") + " around here.");
            } else if (willing < 0.15) {
                d.addProperty("say", "Do it yourself.");
                d.addProperty("thought", "I don't owe " + who + " anything.");
            } else {
                Perception.SeenBlock b = target.get();
                d.add("steps", steps(mine(b)));
                d.addProperty("goal", "gather " + b.id() + " for " + who);
                d.addProperty("say", "On it.");
                d.addProperty("mood", "busy");
            }
            return;
        }
        if (containsAny(text, "follow", "come with", "come here")) {
            if (p.canSee("player", who) && willing >= 0.1) {
                JsonObject s = new JsonObject();
                s.addProperty("kind", "follow");
                s.addProperty("target", who);
                d.add("steps", steps(s));
                d.addProperty("say", "Right behind you.");
            } else {
                d.addProperty("say", "I'd rather not.");
            }
            return;
        }
        if (containsAny(text, "stay", "wait here", "stop")) {
            JsonObject s = new JsonObject();
            s.addProperty("kind", "stay");
            d.add("steps", steps(s));
            d.addProperty("say", "I'll stay here.");
            return;
        }
        if (containsAny(text, "hello", "hi ", "hey", "morning", "evening") || text.equals("hi")) {
            d.addProperty("say", "Hello, " + who + ".");
            d.addProperty("mood", "content");
            return;
        }
        d.addProperty("say", "I'm not sure what you mean, " + who + ".");
    }

    private static void spendFreeTime(CognitionRequest req, MindEvent.Idle idle, Perception p, JsonObject d) {
        if (idle.pressingNeed().equals("purpose") && req.persona().trait(Persona.CONSCIENTIOUSNESS) >= 0.4) {
            p.blocks().stream().filter(b -> b.id().contains("log"))
                    .min(Comparator.comparingDouble(Perception.SeenBlock::distance))
                    .ifPresent(b -> {
                        d.add("steps", steps(mine(b)));
                        d.addProperty("goal", "gather some wood; idle hands");
                        d.addProperty("thought", "I should make myself useful.");
                    });
        } else if (idle.pressingNeed().equals("social")) {
            p.entities().stream().filter(e -> e.kind().equals("player") || e.kind().equals("settler"))
                    .min(Comparator.comparingDouble(Perception.SeenEntity::distance))
                    .ifPresent(e -> {
                        d.addProperty("say", "Nice day, isn't it, " + e.name() + "?");
                        d.addProperty("say_to", e.name());
                    });
        }
    }

    private static JsonObject base(String mood) {
        JsonObject d = new JsonObject();
        d.addProperty("thought", "");
        d.addProperty("mood", mood);
        d.add("steps", new JsonArray());
        d.add("feelings", new JsonArray());
        return d;
    }

    private static JsonObject mine(Perception.SeenBlock b) {
        JsonObject s = new JsonObject();
        s.addProperty("kind", "mine");
        s.addProperty("x", b.x());
        s.addProperty("y", b.y());
        s.addProperty("z", b.z());
        return s;
    }

    private static JsonArray steps(JsonObject... s) {
        JsonArray a = new JsonArray();
        for (JsonObject o : s) a.add(o);
        return a;
    }

    private static void feel(JsonObject d, String who, int change, String why) {
        JsonObject f = new JsonObject();
        f.addProperty("who", who);
        f.addProperty("change", change);
        f.addProperty("why", why);
        d.getAsJsonArray("feelings").add(f);
    }

    private static void remember(JsonObject d, String text, int importance) {
        JsonObject r = new JsonObject();
        r.addProperty("text", text);
        r.addProperty("importance", importance);
        d.add("remember", r);
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }
}
