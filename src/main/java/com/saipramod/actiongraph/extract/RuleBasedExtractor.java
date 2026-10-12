package com.saipramod.actiongraph.extract;

import com.saipramod.actiongraph.model.Model.ActionItem;
import com.saipramod.actiongraph.model.Model.Blocker;
import com.saipramod.actiongraph.model.Model.CommitmentType;
import com.saipramod.actiongraph.model.Model.Decision;
import com.saipramod.actiongraph.model.Model.Extraction;
import com.saipramod.actiongraph.model.Model.ItemStatus;
import com.saipramod.actiongraph.model.Model.Transcript;
import com.saipramod.actiongraph.model.Model.Utterance;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline extractor built from conversational patterns rather than an LLM. It understands
 * three ways a commitment is made in meetings:
 * <ol>
 *   <li>self-commitment: "I'll send the deck by Friday" (explicit) / "I'll take a look" (implicit)</li>
 *   <li>request + acceptance across turns: "Elena, could you add a test?" ... "Sure, by Thursday"</li>
 *   <li>assignment by someone else: "Marcus, you own the rate limit" (+ optional confirmation)</li>
 * </ol>
 * and filters the usual false positives: past tense ("I already sent it"), negation
 * ("I won't be able to"), capability ("I could add..."), declined requests and owner-less
 * suggestions ("someone should...").
 */
public class RuleBasedExtractor implements Extractor {

    static final double EXPLICIT_WITH_DEADLINE = 0.92;
    static final double EXPLICIT = 0.82;
    static final double ACCEPTED_REQUEST = 0.88;
    static final double UNCONFIRMED_ASSIGNMENT = 0.72;
    static final double IMPLICIT = 0.6;

    private static final Pattern SELF_CUE = Pattern.compile(
            "(?i)\\b(i'll|i will|i'm going to|i am going to|i'm gonna|i can have|i'll have|i can|let me|"
                    + "maybe i should|i should|i might|i'll make sure to)\\s+(.*)");
    private static final Pattern HEDGE = Pattern.compile(
            "(?i)\\b(take a look|look into|looking into|see what i can do|try to|try and|circle back|think about|"
                    + "poke at|dig into|check on|probably|maybe|at some point|if i get time|when i get a chance|"
                    + "if nobody|if no one|if i have time|might|should)\\b");
    /** Things people "commit" to that happen inside the meeting itself: not action items. */
    private static final Pattern IN_MEETING = Pattern.compile(
            "(?i)\\b(let me|i'll|i will|i can)\\s+(just\\s+)?(share|pull up|show you|stop sharing|jump in|mute|unmute|"
                    + "rephrase|be clear|clarify|add to that|know|say|start|recap|repeat|drop off|hop off|interrupt|"
                    + "summarize|take notes|turn on|turn off)\\b");
    private static final Pattern NEGATION = Pattern.compile(
            "(?i)\\b(won't|will not|can't|cannot|not able to|unable to|don't think i can|no way)\\b");
    private static final Pattern PAST_OR_HYPOTHETICAL = Pattern.compile(
            "(?i)\\b(already|yesterday|last week|would have|if we had|if i had|i could|i'd|i would)\\b");
    private static final Pattern REQUEST = Pattern.compile(
            "(?i)^(?:(?:okay|ok|so|and|also|please do)[,.]?\\s+)?(?:([A-Z][a-z]+),\\s+)?(?:can|could|would|will) you\\s+(?:please\\s+)?(.+?)[?.!]*$");
    private static final Pattern NEED_REQUEST = Pattern.compile(
            "(?i)^([A-Z][a-z]+),\\s+(?:i need(?: you to)?|please|i'd like you to)\\s+(.+?)[.!]*$");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)^(?:action item:\\s*)?([A-Z][a-z]+)(,\\s+you own|,\\s+you| owns| will| to| is going to)\\s+(.+?)[.!]*$");
    private static final Pattern ACCEPT = Pattern.compile(
            "(?i)^(yes|yeah|yep|yup|sure|ok|okay|will do|can do|on it|absolutely|of course|sounds good|fine|no problem|done deal|got it)\\b");
    private static final Pattern DECLINE = Pattern.compile(
            "(?i)\\b(no|nope|not this week|not right now|can't|cannot|won't|sorry|buried|swamped|no bandwidth)\\b");
    private static final Pattern DECISION = Pattern.compile(
            "(?i)(\\bdecision( for the record| is)?\\s*[:,-]|\\blet's go with\\b|\\bwe('ll| will) go with\\b|\\bwe decided\\b|"
                    + "\\bwe've decided\\b|\\bwe agreed\\b|\\bagreed to\\b|\\bwe're going with\\b|\\bfinal call\\b)");
    private static final Pattern BLOCKER = Pattern.compile(
            "(?i)\\b(blocked on|blocked by|waiting on|waiting for|can't move forward|cannot proceed|blocker is|stuck on)\\b");
    private static final Pattern CLAUSE_SPLIT = Pattern.compile(
            "(?<=[.!?])\\s+|,?\\s+(?:and|but)\\s+(?=(?:I'll|I will|I'm going to|let me)\\b)|,?\\s+and\\s+(?=[A-Z][a-z]+,\\s)");
    private static final Pattern TRAILING = Pattern.compile(
            "(?i)(,?\\s*(promise|honestly|i think|if that works|when i get a chance|if i get time|at some point|on it))+$");

    private record Pending(String addressee, String task, int line, String requestText, DeadlineParser.Deadline deadline,
                           ActionItem assignment) { }

    @Override
    public Extraction extract(Transcript t) {
        List<ActionItem> items = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        List<Blocker> blockers = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        List<String> speakers = t.speakers();
        int seq = 1;

        for (int i = 0; i < t.utterances().size(); i++) {
            Utterance u = t.utterances().get(i);
            String text = clean(u.text());
            int line = u.line();
            pending.removeIf(p -> line - p.line() > 3);

            // 1. Is this turn an answer to a pending request / assignment?
            boolean answeredRequest = false;
            for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
                Pending p = it.next();
                if (!p.addressee().equalsIgnoreCase(u.speaker())) {
                    continue;
                }
                DeadlineParser.Deadline turnDeadline = DeadlineParser.find(text, t.date());
                DeadlineParser.Deadline deadline = turnDeadline != null ? turnDeadline : p.deadline();
                if (DECLINE.matcher(firstWords(text, 6)).find() && !ACCEPT.matcher(text).find()) {
                    if (p.assignment() != null) {
                        items.remove(p.assignment());
                    }
                } else if (ACCEPT.matcher(text).find() || SELF_CUE.matcher(text).find()) {
                    boolean hedged = HEDGE.matcher(text).find() && !ACCEPT.matcher(text).find();
                    if (p.assignment() != null) {
                        items.remove(p.assignment());
                    }
                    items.add(item("A" + seq++, p.task(), u.speaker(), deadline,
                            hedged ? CommitmentType.IMPLICIT : CommitmentType.EXPLICIT,
                            hedged ? IMPLICIT : ACCEPTED_REQUEST, p.requestText(), p.line()));
                } else {
                    continue;
                }
                answeredRequest = true;
                it.remove();
            }

            for (String clause : CLAUSE_SPLIT.split(text)) {
                clause = clause.strip();
                if (clause.isEmpty()) {
                    continue;
                }
                if (DECISION.matcher(clause).find()) {
                    decisions.add(new Decision(clause, clause, line));
                    continue;
                }
                if (BLOCKER.matcher(clause).find()) {
                    blockers.add(new Blocker(clause, u.speaker(), clause, line));
                    continue;
                }
                // 2. A request to someone ("Elena, could you ...", "Can you ...?")
                Matcher rq = REQUEST.matcher(clause);
                Matcher need = NEED_REQUEST.matcher(clause);
                if (rq.matches() || need.matches()) {
                    Matcher m = rq.matches() ? rq : need;
                    String addressee = m.group(1) != null && isSpeaker(m.group(1), speakers) ? m.group(1)
                            : nextSpeaker(t, i);
                    if (addressee != null && !addressee.equalsIgnoreCase(u.speaker())) {
                        DeadlineParser.Deadline d = DeadlineParser.find(text, t.date());
                        pending.add(new Pending(addressee, action(m.group(2), d), line, clause, d, null));
                    }
                    continue;
                }
                // 3. An assignment ("Marcus, you own the rate limit", "Action item: Jun to fix X")
                Matcher as = ASSIGNMENT.matcher(clause);
                if (as.matches() && isSpeaker(as.group(1), speakers) && !as.group(1).equalsIgnoreCase(u.speaker())) {
                    DeadlineParser.Deadline d = DeadlineParser.find(clause, t.date());
                    String task = as.group(2).toLowerCase(Locale.ROOT).contains("own") ? "own " + as.group(3) : as.group(3);
                    ActionItem a = item("A" + seq++, action(task, d), as.group(1), d, CommitmentType.EXPLICIT,
                            d != null ? ACCEPTED_REQUEST : UNCONFIRMED_ASSIGNMENT, clause, line);
                    items.add(a);
                    pending.add(new Pending(as.group(1), a.action(), line, clause, d, a));
                    continue;
                }
                // 4. A self-commitment by the speaker
                if (answeredRequest) {
                    continue;   // "Sure, I'll have it in by Thursday" is the acceptance, not a second item
                }
                Matcher self = SELF_CUE.matcher(clause);
                if (!self.find() || NEGATION.matcher(clause).find() || PAST_OR_HYPOTHETICAL.matcher(clause).find()
                        || IN_MEETING.matcher(clause).find()) {
                    continue;
                }
                String rest = self.group(2);
                if (rest.isBlank() || rest.matches("(?i)(do|do that|do it|try|see)[.!]?")) {
                    continue;
                }
                boolean hedged = HEDGE.matcher(clause).find() || self.group(1).toLowerCase(Locale.ROOT).matches("maybe i should|i should|i might|i can");
                DeadlineParser.Deadline d = DeadlineParser.find(clause, t.date());
                boolean firmCan = self.group(1).equalsIgnoreCase("i can") && d != null && !HEDGE.matcher(clause).find();
                CommitmentType type = hedged && !firmCan ? CommitmentType.IMPLICIT : CommitmentType.EXPLICIT;
                double conf = type == CommitmentType.IMPLICIT ? IMPLICIT : d != null ? EXPLICIT_WITH_DEADLINE : EXPLICIT;
                items.add(item("A" + seq++, action(rest, d), u.speaker(), d, type, conf, clause, line));
            }
        }
        return new Extraction(items, decisions, blockers);
    }

    private static ActionItem item(String id, String action, String owner, DeadlineParser.Deadline d,
                                   CommitmentType type, double confidence, String evidence, int line) {
        return new ActionItem(id, action, owner, d == null ? null : d.date(), d == null ? null : d.text(), type,
                confidence, evidence, line, ItemStatus.OPEN, null, List.of());
    }

    /** "send the revised deck by Friday, promise" -> "send the revised deck". */
    static String action(String raw, DeadlineParser.Deadline d) {
        String a = raw.strip();
        a = a.replaceAll("(?i)^(also|just|go ahead and|definitely|quickly)\\s+", "");
        if (d != null) {
            a = a.replaceAll("(?i),?\\s*(ideally\\s+)?(by|before|on|until|due|for)?\\s*" + Pattern.quote(d.text()) + "('s)?", "");
        }
        a = a.replaceAll("(?i)\\s+(but|so|because|since)\\s+.*$", "");
        a = a.replaceAll("(?i)\\s+if (nobody|no one|i|we).*$", "");
        a = a.replaceAll("[\\s.;!?]+$", "");
        a = TRAILING.matcher(a).replaceAll("");
        return a.replaceAll("[\\s,.;!?]+$", "").strip();
    }

    private static String clean(String text) {
        return text.replace('\u2019', '\'').replaceAll("(?i)\\b(um+|uh+|erm|you know),?\\s*", "").replaceAll("\\s{2,}", " ").strip();
    }

    private static String firstWords(String s, int n) {
        String[] w = s.split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(w, Math.min(n, w.length)));
    }

    private static boolean isSpeaker(String name, List<String> speakers) {
        return speakers.stream().anyMatch(s -> s.equalsIgnoreCase(name) || s.split("\\s+")[0].equalsIgnoreCase(name));
    }

    private static String nextSpeaker(Transcript t, int i) {
        String me = t.utterances().get(i).speaker();
        for (int j = i + 1; j < Math.min(t.utterances().size(), i + 3); j++) {
            if (!t.utterances().get(j).speaker().equalsIgnoreCase(me)) {
                return t.utterances().get(j).speaker();
            }
        }
        return null;
    }

    @Override
    public String name() {
        return "rule-based (offline)";
    }
}
