package dev.hearthkin.mind.cognition;

import dev.hearthkin.mind.MindEvent;
import dev.hearthkin.mind.Perception;
import dev.hearthkin.mind.Persona;
import dev.hearthkin.mind.Relationships;

import java.util.List;

/**
 * Everything needed for one call. {@code system} is stable per settler (cache-friendly);
 * {@code user} holds the volatile situation. The structured fields let the offline
 * scripted mind reason without parsing prose.
 *
 * @param route     which kind of thinking this is, e.g. "decide" or "reflect"
 * @param schema    JSON Schema the reply must follow
 */
public record CognitionRequest(
        String route,
        String settlerName,
        String system,
        String user,
        String schema,
        Persona persona,
        Perception perception,
        List<MindEvent> triggers,
        Relationships relationships,
        List<String> recentMemories) {
}
