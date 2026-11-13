package dev.settlermind.skill;

import dev.settlermind.entity.SettlerEntity;
import org.jetbrains.annotations.Nullable;

/**
 * A deterministic, server-thread-only behaviour. {@link #tick} is called once per
 * server tick until it returns a non-null {@link Outcome}. Skills must be cheap per
 * tick and must verify their own postconditions before returning SUCCEEDED.
 */
public interface Skill {
    String name();

    /** Short description of the current phase, for /settler status. */
    String phase();

    /** @return null while running, otherwise the final outcome. */
    @Nullable Outcome tick(SettlerEntity settler);

    /**
     * Stop now. Must not pretend to roll back mutations that already happened;
     * return PARTIAL in that case.
     */
    Outcome cancel(SettlerEntity settler);
}
