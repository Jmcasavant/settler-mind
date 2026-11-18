package dev.settlermind.skill;

/**
 * The verified result of a skill. A skill only reports SUCCEEDED when the server
 * observed the intended change in the world or inventory, never on its own say-so.
 *
 * @param code   machine-readable reason, e.g. "ok", "protected", "target_changed"
 * @param detail human-readable evidence, e.g. "+1 minecraft:oak_log"
 */
public record Outcome(Status status, String code, String detail) {
    public enum Status { SUCCEEDED, PARTIAL, FAILED, CANCELLED, UNCERTAIN }

    public static Outcome ok(String detail) { return new Outcome(Status.SUCCEEDED, "ok", detail); }
    public static Outcome fail(String code, String detail) { return new Outcome(Status.FAILED, code, detail); }
    public static Outcome partial(String code, String detail) { return new Outcome(Status.PARTIAL, code, detail); }

    @Override
    public String toString() {
        return status + "(" + code + (detail.isEmpty() ? "" : ": " + detail) + ")";
    }
}
