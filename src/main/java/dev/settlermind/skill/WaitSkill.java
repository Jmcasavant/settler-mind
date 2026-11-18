package dev.settlermind.skill;

import dev.settlermind.entity.SettlerEntity;
import org.jetbrains.annotations.Nullable;

/** Do nothing for a while (reflexes such as eating still run). */
public final class WaitSkill implements Skill {
    private final int total;
    private int ticks;

    public WaitSkill(int seconds) {
        this.total = Math.max(1, Math.min(60, seconds)) * 20;
    }

    @Override public String name() { return "wait " + total / 20 + "s"; }
    @Override public String phase() { return (total - ticks) / 20 + "s left"; }

    @Override
    public @Nullable Outcome tick(SettlerEntity s) {
        return ++ticks >= total ? Outcome.ok("waited " + total / 20 + "s") : null;
    }

    @Override
    public Outcome cancel(SettlerEntity s) {
        return new Outcome(Outcome.Status.CANCELLED, "cancelled", "");
    }
}
