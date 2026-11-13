package dev.settlermind.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.settlermind.SettlerMind;
import dev.settlermind.mind.cognition.RouteConfig;
import dev.settlermind.mind.cognition.UsageLedger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code config/settlermind.json}. Model choice is deliberately data, not code: each kind
 * of thinking ("route") names its own provider, model and effort, so they can be
 * changed and compared with {@code /settler usage} without a rebuild. See docs/MODELS.md.
 *
 * <p>Defaults are free and offline ("scripted"). Paid inference is opt-in.
 */
public final class SettlerMindConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Route name to how it is served. Unknown routes fall back to "decide". */
    public Map<String, RouteConfig> routes = new LinkedHashMap<>();

    public Claude claude = new Claude();
    public Budget budget = new Budget();
    /** USD per million tokens, used only for the ledger's estimates. Update when prices change. */
    public Map<String, UsageLedger.Price> prices = new LinkedHashMap<>();

    public int hearingRadius = 24;
    public int idleThinkSeconds = 90;
    /** When a remote provider is configured, players must opt in before their chat is sent to it. */
    public boolean requireChatConsent = true;

    public static final class Claude {
        /** Environment variable holding the API key; the key itself is never stored in config. */
        public String apiKeyEnv = "ANTHROPIC_API_KEY";
        public int timeoutSeconds = 45;
        /** Ask the API to retry refused requests on a suitable model (server-side fallbacks). */
        public boolean refusalFallbacks = true;
    }

    public static final class Budget {
        public int callsPerSettlerPerHour = 40;
        public int callsPerServerPerHour = 240;
        /** Hard stop for estimated spend per real-time day across all routes. */
        public double maxEstimatedUsdPerDay = 2.00;
    }

    public static SettlerMindConfig defaults() {
        SettlerMindConfig c = new SettlerMindConfig();
        c.routes.put("decide", new RouteConfig("scripted", "claude-opus-5-5", "low", 1500));
        c.routes.put("reflect", new RouteConfig("scripted", "claude-opus-5-5", "medium", 1500));
        c.routes.put("plan", new RouteConfig("scripted", "claude-opus-5-5", "medium", 1000));
        c.prices.put("claude-opus-5-5", new UsageLedger.Price(4.00, 20.00, 0.20, 5.00));
        c.prices.put("claude-sonnet-5-5", new UsageLedger.Price(2.00, 10.00, 0.10, 2.50));
        c.prices.put("claude-haiku-5-5", new UsageLedger.Price(0.10, 0.50, 0.01, 0.125));
        return c;
    }

    public RouteConfig route(String name) {
        return routes.getOrDefault(name, routes.get("decide"));
    }

    public boolean anyRemoteProvider() {
        return routes.values().stream().anyMatch(r -> !r.provider().equals("scripted"));
    }

    public static SettlerMindConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("settlermind.json");
        SettlerMindConfig defaults = defaults();
        try {
            if (Files.exists(file)) {
                SettlerMindConfig c = GSON.fromJson(Files.readString(file), SettlerMindConfig.class);
                if (c != null) {
                    defaults.routes.forEach(c.routes::putIfAbsent);
                    defaults.prices.forEach(c.prices::putIfAbsent);
                    return c;
                }
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(defaults));
        } catch (IOException | RuntimeException e) {
            SettlerMind.LOG.error("Could not read {}; using offline defaults", file, e);
        }
        return defaults;
    }
}
