package dev.settlermind.mind.cognition;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.google.gson.Gson;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Claude via the official Anthropic Java SDK. Runs on the mind executor, never on the
 * server thread. The stable system prompt is marked for prompt caching; the reply is
 * constrained to the route's JSON Schema with structured outputs.
 */
public final class ClaudeCognition implements Cognition {
    private static final Gson GSON = new Gson();

    private final AnthropicClient client;
    private final Executor executor;
    private final boolean refusalFallbacks;

    public ClaudeCognition(String apiKey, Duration timeout, boolean refusalFallbacks, Executor executor) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(timeout)
                .maxRetries(1)
                .build();
        this.executor = executor;
        this.refusalFallbacks = refusalFallbacks;
    }

    @Override
    public String provider() { return "claude"; }

    @Override
    public CompletableFuture<CognitionResult> complete(CognitionRequest req, RouteConfig route) {
        return CompletableFuture.supplyAsync(() -> call(req, route), executor);
    }

    private CognitionResult call(CognitionRequest req, RouteConfig route) {
        long start = System.nanoTime();
        try {
            MessageCreateParams.Builder b = MessageCreateParams.builder()
                    .model(route.model())
                    .maxTokens(route.maxTokens())
                    .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                            .text(req.system())
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build()))
                    .addUserMessage(req.user())
                    .outputConfig(OutputConfig.builder()
                            .effort(OutputConfig.Effort.of(route.effort()))
                            .format(JsonOutputFormat.builder().schema(schema(req.schema())).build())
                            .build());
            if (refusalFallbacks && supportsServerFallbacks(route.model())) {
                // If a safety classifier declines, the API retries on a suitable model itself.
                b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
                b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
            }
            Message msg = client.messages().create(b.build());
            long ms = (System.nanoTime() - start) / 1_000_000;

            long in = msg.usage().inputTokens();
            long out = msg.usage().outputTokens();
            long cacheRead = msg.usage().cacheReadInputTokens().orElse(0L);
            long cacheWrite = msg.usage().cacheCreationInputTokens().orElse(0L);
            String model = msg.model().asString();

            if (msg.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
                return new CognitionResult(null, provider(), model, in, out, cacheRead, cacheWrite, ms, "refused");
            }
            if (msg.stopReason().map(r -> r.equals(StopReason.MAX_TOKENS)).orElse(false)) {
                return new CognitionResult(null, provider(), model, in, out, cacheRead, cacheWrite, ms, "max_tokens");
            }
            String text = msg.content().stream()
                    .flatMap(c -> c.text().stream())
                    .map(TextBlock::text)
                    .collect(Collectors.joining());
            return new CognitionResult(text, provider(), model, in, out, cacheRead, cacheWrite, ms, null);
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return CognitionResult.failed(provider(), route.model(), ms, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static boolean supportsServerFallbacks(String model) {
        return model.startsWith("claude-opus-5") || model.startsWith("claude-sonnet-5-5") || model.startsWith("claude-fable-5");
    }

    @SuppressWarnings("unchecked")
    private static JsonOutputFormat.Schema schema(String json) {
        Map<String, Object> map = GSON.fromJson(json, Map.class);
        JsonOutputFormat.Schema.Builder sb = JsonOutputFormat.Schema.builder();
        map.forEach((k, v) -> sb.putAdditionalProperty(k, JsonValue.from(v)));
        return sb.build();
    }
}
