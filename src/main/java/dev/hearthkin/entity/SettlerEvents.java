package dev.hearthkin.entity;

import dev.hearthkin.mind.Decision;
import dev.hearthkin.skill.Outcome;

/**
 * What a settler's body reports upward. Keeps the entity independent of the AI layer:
 * the mind host installs a listener; with none installed (e.g. in pure body tests) the
 * body works on its own.
 */
public interface SettlerEvents {
    void onStepFinished(SettlerEntity settler, Decision.Step step, Outcome outcome, boolean planDone);

    void onHurt(SettlerEntity settler, String attacker, float amount);

    void onDismissed(SettlerEntity settler);

    SettlerEvents NONE = new SettlerEvents() {
        public void onStepFinished(SettlerEntity s, Decision.Step step, Outcome o, boolean done) { }
        public void onHurt(SettlerEntity s, String attacker, float amount) { }
        public void onDismissed(SettlerEntity s) { }
    };
}
