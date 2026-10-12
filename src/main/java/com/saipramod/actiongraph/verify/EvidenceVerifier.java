package com.saipramod.actiongraph.verify;

import com.saipramod.actiongraph.model.Model.ActionItem;
import com.saipramod.actiongraph.model.Model.Blocker;
import com.saipramod.actiongraph.model.Model.Decision;
import com.saipramod.actiongraph.model.Model.Extraction;
import com.saipramod.actiongraph.model.Model.ItemStatus;
import com.saipramod.actiongraph.model.Model.Transcript;
import com.saipramod.actiongraph.model.Model.Utterance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The anti-hallucination gate. Every extracted item must quote the transcript:
 * <ul>
 *   <li>exact quote (after normalising case, punctuation, fillers) on the cited line: accepted</li>
 *   <li>exact quote on another line: accepted, line corrected</li>
 *   <li>close paraphrase of one sentence (token overlap >= 0.8): kept, sent to human review</li>
 *   <li>anything else: rejected as unsupported</li>
 * </ul>
 * Then confidence-based routing: low confidence, soft commitments, owners who were not in
 * the meeting and deadlines already in the past go to the review queue.
 */
public class EvidenceVerifier {

    public static final double REVIEW_THRESHOLD = 0.7;
    static final double PARAPHRASE_MIN_OVERLAP = 0.8;

    public enum Verdict { EXACT, EXACT_OTHER_LINE, PARAPHRASE, UNSUPPORTED }

    public record Rejected(ActionItem item, String reason) { }

    public record Result(List<ActionItem> items, List<Decision> decisions, List<Blocker> blockers,
                         List<Rejected> rejected, int candidates) {
        public double hallucinationRate() {
            return candidates == 0 ? 0 : (double) rejected.size() / candidates;
        }
    }

    public Result verify(Transcript t, Extraction x) {
        List<ActionItem> kept = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (ActionItem item : x.actions()) {
            Located loc = locate(t, item.evidence(), item.evidenceLine());
            if (loc.verdict() == Verdict.UNSUPPORTED) {
                rejected.add(new Rejected(item, "evidence quote not found in transcript"));
                continue;
            }
            ActionItem fixed = item.withEvidence(item.evidence(), loc.line());
            List<String> reasons = new ArrayList<>();
            double conf = fixed.confidence();
            if (loc.verdict() == Verdict.PARAPHRASE) {
                reasons.add("evidence is a paraphrase, not an exact quote");
                conf = Math.min(conf, 0.5);
            }
            if (!isParticipantOrMentioned(t, fixed)) {
                reasons.add("owner '" + fixed.owner() + "' is not a participant");
                conf = Math.min(conf, 0.5);
            }
            if (fixed.deadline() != null && fixed.deadline().isBefore(t.date())) {
                reasons.add("deadline is before the meeting date");
                conf = Math.min(conf, 0.5);
            }
            if (fixed.type() == com.saipramod.actiongraph.model.Model.CommitmentType.IMPLICIT) {
                reasons.add("soft commitment; confirm with owner");
            }
            if (conf < REVIEW_THRESHOLD && reasons.isEmpty()) {
                reasons.add("low confidence");
            }
            boolean review = conf < REVIEW_THRESHOLD || !reasons.isEmpty();
            kept.add(fixed.withReview(conf, review ? ItemStatus.NEEDS_REVIEW : ItemStatus.OPEN, reasons));
        }
        List<Decision> decisions = x.decisions().stream()
                .filter(d -> locate(t, d.evidence(), d.evidenceLine()).verdict() != Verdict.UNSUPPORTED).toList();
        List<Blocker> blockers = x.blockers().stream()
                .filter(b -> locate(t, b.evidence(), b.evidenceLine()).verdict() != Verdict.UNSUPPORTED).toList();
        return new Result(kept, decisions, blockers, rejected, x.actions().size());
    }

    public record Located(Verdict verdict, int line) { }

    public Located locate(Transcript t, String quote, int citedLine) {
        if (quote == null || quote.isBlank()) {
            return new Located(Verdict.UNSUPPORTED, citedLine);
        }
        String q = normalize(quote);
        if (q.length() < 4) {
            return new Located(Verdict.UNSUPPORTED, citedLine);
        }
        Utterance cited = t.at(citedLine);
        if (cited != null && normalize(cited.text()).contains(q)) {
            return new Located(Verdict.EXACT, citedLine);
        }
        for (Utterance u : t.utterances()) {
            if (normalize(u.text()).contains(q)) {
                return new Located(Verdict.EXACT_OTHER_LINE, u.line());
            }
        }
        Set<String> qTokens = tokens(q);
        double best = 0;
        int bestLine = citedLine;
        for (Utterance u : t.utterances()) {
            for (String sentence : u.text().split("(?<=[.!?])\\s+")) {
                Set<String> s = tokens(normalize(sentence));
                Set<String> inter = new HashSet<>(qTokens);
                inter.retainAll(s);
                double overlap = qTokens.isEmpty() ? 0 : (double) inter.size() / Math.max(qTokens.size(), s.size());
                if (overlap > best) {
                    best = overlap;
                    bestLine = u.line();
                }
            }
        }
        return best >= PARAPHRASE_MIN_OVERLAP ? new Located(Verdict.PARAPHRASE, bestLine)
                : new Located(Verdict.UNSUPPORTED, citedLine);
    }

    private static boolean isParticipantOrMentioned(Transcript t, ActionItem item) {
        if (item.owner() == null || item.owner().isBlank()) {
            return false;
        }
        String owner = item.owner().strip().toLowerCase(Locale.ROOT);
        return t.speakers().stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).equals(owner)
                || s.toLowerCase(Locale.ROOT).split("\\s+")[0].equals(owner.split("\\s+")[0]))
                || t.utterances().stream().anyMatch(u -> u.text().toLowerCase(Locale.ROOT).contains(owner));
    }

    public static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("\\b(um+|uh+|erm|you know)\\b", " ")
                .replaceAll("[^a-z0-9' ]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static Set<String> tokens(String normalized) {
        return new HashSet<>(Arrays.asList(normalized.split(" ")));
    }
}
