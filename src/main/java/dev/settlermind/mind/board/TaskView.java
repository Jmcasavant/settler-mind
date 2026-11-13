package dev.settlermind.mind.board;

/**
 * Immutable snapshot of a task as one settler sees it (perception and prompts).
 *
 * @param item       full item id, e.g. {@code minecraft:oak_log}
 * @param claimant   claimant's display name, or null
 * @param postedBy   poster's display name
 * @param note       the poster's note, or "" when there is none or it may not be shown to this settler
 * @param mine       CLAIMED by this settler (by id)
 * @param postedByMe posted by this settler (by id and kind), so it may cancel it
 */
public record TaskView(String id, String item, int count, int progress, BoardTask.Status status, String claimant,
                       String postedBy, String note, int revision, boolean mine, boolean postedByMe) {
    static TaskView of(BoardTask t, String forSettlerId, boolean noteVisible) {
        boolean mine = t.claimedBy(forSettlerId);
        boolean postedByMe = t.postedBy(BoardTask.KIND_SETTLER, forSettlerId);
        String note = noteVisible && t.note != null ? t.note : "";
        return new TaskView(t.id, t.item, t.count, t.progress, t.status, t.claimantName, t.posterName,
                note, t.revision, mine, postedByMe);
    }
}
