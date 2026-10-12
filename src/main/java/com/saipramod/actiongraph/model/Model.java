package com.saipramod.actiongraph.model;

import java.time.LocalDate;
import java.util.List;

/** The structured schema everything is extracted into. */
public final class Model {

    private Model() { }

    /** One speaker turn. {@code line} is 1-based and is what evidence citations point to. */
    public record Utterance(int line, String speaker, String timestamp, String text) { }

    public record Transcript(String title, LocalDate date, List<Utterance> utterances) {
        public List<String> speakers() {
            return utterances.stream().map(Utterance::speaker).distinct().toList();
        }

        public Utterance at(int line) {
            return utterances.stream().filter(u -> u.line() == line).findFirst().orElse(null);
        }
    }

    /** EXPLICIT: a firm commitment ("I'll send it Friday"). IMPLICIT: a soft one ("I'll take a look"). */
    public enum CommitmentType { EXPLICIT, IMPLICIT }

    public enum ItemStatus { OPEN, DONE, NEEDS_REVIEW, REJECTED }

    /**
     * @param deadline     resolved date, null when none was stated
     * @param deadlineText the words used ("by Friday"), kept for review
     * @param evidence     exact quote from the transcript supporting the item
     * @param evidenceLine line of the quote
     * @param confidence   0..1; below the review threshold the item goes to a human
     */
    public record ActionItem(String id, String action, String owner, LocalDate deadline, String deadlineText,
                             CommitmentType type, double confidence, String evidence, int evidenceLine,
                             ItemStatus status, String meetingId, List<String> reviewReasons) {

        public ActionItem withStatus(ItemStatus s) {
            return new ActionItem(id, action, owner, deadline, deadlineText, type, confidence, evidence, evidenceLine,
                    s, meetingId, reviewReasons);
        }

        public ActionItem withReview(double newConfidence, ItemStatus s, List<String> reasons) {
            return new ActionItem(id, action, owner, deadline, deadlineText, type, newConfidence, evidence,
                    evidenceLine, s, meetingId, reasons);
        }

        public ActionItem withEvidence(String quote, int line) {
            return new ActionItem(id, action, owner, deadline, deadlineText, type, confidence, quote, line, status,
                    meetingId, reviewReasons);
        }

        public ActionItem withMeeting(String id2, String meeting) {
            return new ActionItem(id2, action, owner, deadline, deadlineText, type, confidence, evidence, evidenceLine,
                    status, meeting, reviewReasons);
        }
    }

    public record Decision(String text, String evidence, int evidenceLine) { }

    public record Blocker(String text, String owner, String evidence, int evidenceLine) { }

    /** Raw extractor output, before verification. */
    public record Extraction(List<ActionItem> actions, List<Decision> decisions, List<Blocker> blockers) { }
}
