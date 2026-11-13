package dev.settlermind.entity;

import dev.settlermind.mind.Decision;
import dev.settlermind.skill.Outcome;
import net.minecraft.util.math.BlockPos;

/**
 * What a settler's body reports upward, and the few questions it asks. Keeps the entity independent of
 * the AI layer: the mind host installs a listener; with none installed (e.g. in pure body tests) the
 * body works on its own: board steps fail with "no_board" and every block may be worked.
 */
public interface SettlerEvents {
    void onStepFinished(SettlerEntity settler, Decision.Step step, Outcome outcome, boolean planDone);

    void onHurt(SettlerEntity settler, String attacker, float amount);

    void onDismissed(SettlerEntity settler);

    /** Carry out an instant board step (post_task, claim_task, release_task) and report the result. */
    default Outcome onBoardStep(SettlerEntity settler, Decision.Step step) {
        return Outcome.fail("no_board", "there is no settlement board here");
    }

    /**
     * Reserve {@code pos} for this settler before working it (refreshes an existing reservation).
     * @return false if someone else is working on it.
     */
    default boolean reserveBlock(SettlerEntity settler, BlockPos pos) { return true; }

    /** The settler has stopped working on {@code pos} (finished, failed or cancelled). */
    default void releaseBlock(SettlerEntity settler, BlockPos pos) { }

    SettlerEvents NONE = new SettlerEvents() {
        public void onStepFinished(SettlerEntity s, Decision.Step step, Outcome o, boolean done) { }
        public void onHurt(SettlerEntity s, String attacker, float amount) { }
        public void onDismissed(SettlerEntity s) { }
    };
}
