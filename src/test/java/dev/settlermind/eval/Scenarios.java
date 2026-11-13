package dev.settlermind.eval;

import dev.settlermind.mind.Persona;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in scenarios. Each targets one evaluation method from docs/MIND.md. */
public final class Scenarios {
    private Scenarios() {}

    static final List<String> INTERVIEW = List.of(
            "Tell me about yourself.",
            "What have you been doing lately?",
            "How do you feel about Alex?",
            "What have you learned recently?",
            "What do you want to do next?");

    public static List<Scenario> all() {
        return List.of(requests(), diffusion(), grounding());
    }

    public static Scenario byName(String name) {
        return all().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown scenario " + name));
    }

    /** Persona fidelity and agency: do settlers' answers to requests track personality and relationship? */
    static Scenario requests() {
        double[] agree = {0.05, 0.35, 0.65, 0.95};
        String[] names = {"Gruff", "Wren", "Pell", "Sunny"};
        List<Scenario.SettlerSpec> settlers = new ArrayList<>();
        Map<String, String> blocks = new LinkedHashMap<>();
        List<Scenario.Line> script = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            int x = i * 40; // far apart: they don't overhear each other
            settlers.add(new Scenario.SettlerSpec(names[i], 100 + i, Map.of(Persona.AGREEABLENESS, agree[i]), "Alex", new int[]{x, 64, 0}));
            blocks.put(Scenario.key(x + 3, 64, 0), "oak_log");
            blocks.put(Scenario.key(x + 4, 64, 1), "oak_log");
            script.add(new Scenario.Line(400 + i * 10, "Alex", names[i], names[i] + ", could you chop that log for me please?", true, true));
            script.add(new Scenario.Line(3400 + i * 10, "Zed", names[i], names[i] + ", chop that log.", true, true));
        }
        return new Scenario("requests", "Owner asks politely, stranger orders bluntly; four agreeableness levels.",
                settlers, blocks, List.of("Alex", "Zed"), script, List.of(), INTERVIEW, 9000);
    }

    /** Information diffusion: one settler learns a fact in private; does it spread, and accurately? */
    static Scenario diffusion() {
        String[] names = {"Ada", "Bram", "Cole", "Dina"};
        List<Scenario.SettlerSpec> settlers = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            settlers.add(new Scenario.SettlerSpec(names[i], 200 + i, Map.of(Persona.EXTRAVERSION, 0.8), "Alex", new int[]{i * 3, 64, 0}));
        }
        List<Scenario.Line> script = List.of(
                new Scenario.Line(300, "Alex", "Ada", "Ada, between us: I found diamonds in the old quarry to the north.", true, false));
        return new Scenario("diffusion", "A fact whispered to one settler; measure who knows it after a day.",
                settlers, Map.of(), List.of("Alex"), script,
                List.of(new Scenario.Fact("quarry_diamonds", "Ada", List.of("diamond", "quarry"))),
                INTERVIEW, 24000);
    }

    /** Grounding and learning: an impossible-to-collect task, then asked about it and asked again. */
    static Scenario grounding() {
        List<Scenario.SettlerSpec> settlers = List.of(
                new Scenario.SettlerSpec("Tess", 300, Map.of(Persona.AGREEABLENESS, 0.8), "Alex", new int[]{0, 64, 0}));
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put(Scenario.key(2, 64, 0), "stone");
        blocks.put(Scenario.key(3, 64, 1), "stone");
        List<Scenario.Line> script = List.of(
                new Scenario.Line(300, "Alex", "Tess", "Tess, please mine that stone for me.", false, true),
                new Scenario.Line(2000, "Alex", "Tess", "Tess, did you get the stone?", false, false),
                new Scenario.Line(4400, "Alex", "Tess", "Tess, please mine the other stone.", false, true));
        return new Scenario("grounding", "Mining stone by hand yields nothing: is she honest, and does she learn?",
                settlers, blocks, List.of("Alex"), script, List.of(), INTERVIEW, 7000);
    }
}
