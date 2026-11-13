package dev.settlermind.mind.board;

/** Immutable snapshot of a task as one settler sees it (perception and prompts). */
public record TaskView(String id, String item, int count, int progress, BoardTask.Status status, String claimant,
                       String postedBy, String note, int revision, boolean mine) {
    static TaskView of(BoardTask t, String forSettler) {
        boolean mine = t.status == BoardTask.Status.CLAIMED && t.claimant != null && t.claimant.equals(forSettler);
        return new TaskView(t.id, t.item, t.count, t.progress, t.status, t.claimant, t.postedBy,
                t.note == null ? "" : t.note, t.revision, mine);
    }
}
