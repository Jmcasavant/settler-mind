package dev.hearthkin.mind;

/** Things that happen to a settler and may make it think. */
public sealed interface MindEvent {
    /** One-line description for memory and for the "what just happened" part of the prompt. */
    String describe();

    /** Whether this event should wake the mind up to decide something now. */
    boolean wakes();

    /** Someone said something within earshot. */
    record Heard(String speaker, String speakerKind, String text, boolean addressedToMe, boolean shareable) implements MindEvent {
        public String describe() {
            return shareable ? speaker + " said: \"" + text + "\""
                             : speaker + " said something (their words aren't shared with you)";
        }
        public boolean wakes() { return addressedToMe; }
    }

    /** A plan step finished, with the game's verified outcome. */
    record ActionFinished(String step, String kind, String status, String code, String detail, boolean planDone) implements MindEvent {
        public String describe() {
            String d = detail == null || detail.isBlank() ? "" : " (" + detail + ")";
            return "Your action \"" + step + "\" " + status.toLowerCase() + ": " + code + d;
        }
        /** Only wake for failures or when the whole plan is done, not after every step. */
        public boolean wakes() { return failed() || planDone; }
        public boolean failed() { return !status.equals("SUCCEEDED"); }
    }

    /** Something hurt the settler. */
    record Hurt(String by, float amount) implements MindEvent {
        public String describe() { return "You were hurt by " + by + " (" + amount + " damage)"; }
        public boolean wakes() { return true; }
    }

    /** Nothing to do for a while: the mind decides how to spend free time. */
    record Idle(String pressingNeed) implements MindEvent {
        public String describe() { return "You have nothing to do. Your most pressing need is " + pressingNeed + "."; }
        public boolean wakes() { return true; }
    }

    /** Someone new came into view. */
    record Noticed(String kind, String name) implements MindEvent {
        public String describe() { return "You notice " + kind + " " + name + " nearby."; }
        public boolean wakes() { return false; }
    }
}
